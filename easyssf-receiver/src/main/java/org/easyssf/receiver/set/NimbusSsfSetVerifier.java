package org.easyssf.receiver.set;

import java.net.URI;
import java.security.Key;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.support.SsfAssert;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.http.SsfHttpRequest;
import org.easyssf.receiver.http.SsfHttpResponse;
import org.easyssf.receiver.transmitter.SsfTransmitterUnavailableException;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.BadJWSException;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.Resource;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * {@link SsfSetVerifier} that verifies SET signatures against the transmitter's JWK Set
 * and validates the claims required by RFC 8417 and the SSF profile.
 *
 * <p>
 * The JWK Set location is resolved on first use, so a transmitter that is not reachable
 * does not prevent the receiver from starting. The JWK Set is cached and fetched again
 * when a SET is signed with a key that is not in it.
 */
public class NimbusSsfSetVerifier implements SsfSetVerifier {

    /**
     * The media type a SET must be explicitly typed with (RFC 8417, section 2.3).
     */
    public static final String SECEVENT_JWT_TYPE = "secevent+jwt";

    private final String issuer;

    private final Supplier<String> jwkSetUri;

    private final SsfHttpClient httpClient;

    private Supplier<? extends Collection<String>> expectedAudiences = List::of;

    private Set<JWSAlgorithm> acceptedAlgorithms = Set.of(JWSAlgorithm.RS256);

    private int minRsaKeySize = 2048;

    private boolean requireTypeHeader = true;

    private Duration clockSkew = Duration.ofSeconds(60);

    private Clock clock = Clock.systemUTC();

    private volatile ConfigurableJWTProcessor<SecurityContext> processor;

    /**
     * @param issuer the issuer every SET must be issued by
     * @param jwkSetUri supplies the location of the transmitter's JWK Set, may throw
     * {@link SsfTransmitterUnavailableException}
     * @param httpClient used to fetch the JWK Set
     */
    public NimbusSsfSetVerifier(String issuer, Supplier<String> jwkSetUri, SsfHttpClient httpClient) {
        SsfAssert.hasText(issuer, "issuer must not be empty");
        SsfAssert.notNull(jwkSetUri, "jwkSetUri must not be null");
        SsfAssert.notNull(httpClient, "httpClient must not be null");
        this.issuer = issuer;
        this.jwkSetUri = jwkSetUri;
        this.httpClient = httpClient;
    }

    /**
     * Sets the audience every SET must be addressed to, {@code null} to accept any.
     */
    public void setExpectedAudience(String expectedAudience) {
        List<String> audiences = (expectedAudience != null) ? List.of(expectedAudience) : List.of();
        this.expectedAudiences = () -> audiences;
    }

    /**
     * Sets a supplier of the audiences a SET must be addressed to, consulted for every
     * SET. A SET has to be addressed to at least one of them, any audience is accepted
     * while the supplier returns none.
     */
    public void setExpectedAudiences(Supplier<? extends Collection<String>> expectedAudiences) {
        SsfAssert.notNull(expectedAudiences, "expectedAudiences must not be null");
        this.expectedAudiences = expectedAudiences;
    }

    /**
     * Sets the JWS algorithms SETs may be signed with, for example {@code RS256}. Only
     * asymmetric signature algorithms are allowed. Has to be called before the first SET
     * is verified.
     * @throws IllegalArgumentException if an algorithm is unknown or not a signature
     * algorithm with a public key
     */
    public void setAcceptedAlgorithms(Collection<String> acceptedAlgorithms) {
        SsfAssert.notEmpty(acceptedAlgorithms, "acceptedAlgorithms must not be empty");
        Set<JWSAlgorithm> algorithms = new HashSet<>();
        for (String name : acceptedAlgorithms) {
            JWSAlgorithm algorithm = JWSAlgorithm.parse(name.trim());
            SsfAssert.isTrue(JWSAlgorithm.Family.SIGNATURE.contains(algorithm),
                    "Not an asymmetric signature algorithm: " + name);
            algorithms.add(algorithm);
        }
        this.acceptedAlgorithms = Set.copyOf(algorithms);
    }

    /**
     * Sets the minimum size of RSA signing keys in bits, {@code 0} to accept any.
     */
    public void setMinRsaKeySize(int minRsaKeySize) {
        this.minRsaKeySize = minRsaKeySize;
    }

    /**
     * Whether a SET must have a {@code typ} header of {@value #SECEVENT_JWT_TYPE}.
     */
    public void setRequireTypeHeader(boolean requireTypeHeader) {
        this.requireTypeHeader = requireTypeHeader;
    }

    /**
     * Sets how far in the future a SET may be issued according to the local clock.
     */
    public void setClockSkew(Duration clockSkew) {
        SsfAssert.notNull(clockSkew, "clockSkew must not be null");
        this.clockSkew = clockSkew;
    }

    public void setClock(Clock clock) {
        SsfAssert.notNull(clock, "clock must not be null");
        this.clock = clock;
    }

    @Override
    public SsfEventToken verify(String encodedSet) {
        ConfigurableJWTProcessor<SecurityContext> processor = getProcessor();
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(encodedSet);
        }
        catch (ParseException ex) {
            throw invalid(SsfSetVerificationException.INVALID_REQUEST, "The SET is not a signed JWT", ex);
        }
        JWTClaimsSet claims;
        try {
            claims = processor.process(jwt, null);
        }
        catch (BadJWSException ex) {
            throw invalid(SsfSetVerificationException.INVALID_KEY, "The signature of the SET is invalid", ex);
        }
        catch (BadJOSEException ex) {
            // Nimbus reports a key that is unknown, too small or of another algorithm
            // this way
            boolean noAcceptableKey = String.valueOf(ex.getMessage()).contains("no matching key");
            throw invalid(
                    noAcceptableKey ? SsfSetVerificationException.INVALID_KEY
                            : SsfSetVerificationException.INVALID_REQUEST,
                    "The SET was rejected: " + ex.getMessage(), ex);
        }
        catch (RateLimitReachedException ex) {
            // the JWK Set was fetched a moment ago and does not contain the key
            throw invalid(SsfSetVerificationException.INVALID_KEY, "The SET is signed with an unknown key", ex);
        }
        catch (KeySourceException ex) {
            // neither malformed nor invalid: the transmitter's keys could not be
            // retrieved
            throw new SsfTransmitterUnavailableException("Could not retrieve the keys to verify the SET", ex);
        }
        catch (JOSEException ex) {
            throw invalid(SsfSetVerificationException.INVALID_REQUEST, "The SET was rejected: " + ex.getMessage(), ex);
        }
        validate(jwt, claims);
        return toEventToken(claims);
    }

    private void validate(SignedJWT jwt, JWTClaimsSet claims) {
        JOSEObjectType type = jwt.getHeader().getType();
        if (this.requireTypeHeader && (type == null || !SECEVENT_JWT_TYPE.equalsIgnoreCase(type.getType()))) {
            throw invalid(SsfSetVerificationException.INVALID_REQUEST,
                    "The SET must be typed with a typ header of " + SECEVENT_JWT_TYPE, null);
        }
        if (!this.issuer.equals(claims.getIssuer())) {
            throw invalid(SsfSetVerificationException.INVALID_ISSUER,
                    "The SET was not issued by the expected transmitter", null);
        }
        Collection<String> expectedAudiences = this.expectedAudiences.get();
        if (!expectedAudiences.isEmpty() && Collections.disjoint(claims.getAudience(), expectedAudiences)) {
            throw invalid(SsfSetVerificationException.INVALID_AUDIENCE, "The SET is not addressed to this receiver",
                    null);
        }
        if (claims.getJWTID() == null || claims.getJWTID().isBlank()) {
            throw invalid(SsfSetVerificationException.INVALID_REQUEST, "The SET has no jti claim", null);
        }
        Date issuedAt = claims.getIssueTime();
        if (issuedAt == null) {
            throw invalid(SsfSetVerificationException.INVALID_REQUEST, "The SET has no iat claim", null);
        }
        if (issuedAt.toInstant().isAfter(this.clock.instant().plus(this.clockSkew))) {
            throw invalid(SsfSetVerificationException.INVALID_REQUEST, "The SET was issued in the future", null);
        }
        if (!(claims.getClaim("events") instanceof Map<?, ?> events) || events.isEmpty()) {
            throw invalid(SsfSetVerificationException.INVALID_REQUEST, "The SET has no events claim", null);
        }
    }

    private static SsfSetVerificationException invalid(String errorCode, String message, Throwable cause) {
        return new SsfSetVerificationException(errorCode, message, cause);
    }

    @SuppressWarnings("unchecked")
    private static SsfEventToken toEventToken(JWTClaimsSet claims) {
        Map<String, Object> events = Collections.unmodifiableMap((Map<String, Object>) claims.getClaim("events"));
        Map<String, Object> subjectId = (claims.getClaim("sub_id") instanceof Map<?, ?> map)
                ? Collections.unmodifiableMap((Map<String, Object>) map) : null;
        String txn = (claims.getClaim("txn") instanceof String value) ? value : null;
        Instant issuedAt = claims.getIssueTime().toInstant();
        return new SsfEventToken(claims.getJWTID(), claims.getIssuer(), issuedAt, List.copyOf(claims.getAudience()),
                events, subjectId, txn, Collections.unmodifiableMap(claims.toJSONObject()));
    }

    private ConfigurableJWTProcessor<SecurityContext> getProcessor() {
        ConfigurableJWTProcessor<SecurityContext> processor = this.processor;
        if (processor == null) {
            synchronized (this) {
                processor = this.processor;
                if (processor == null) {
                    processor = createProcessor();
                    this.processor = processor;
                }
            }
        }
        return processor;
    }

    private ConfigurableJWTProcessor<SecurityContext> createProcessor() {
        URI jwkSetUri = URI.create(this.jwkSetUri.get());
        JWKSource<SecurityContext> jwkSource;
        try {
            jwkSource = JWKSourceBuilder.<SecurityContext>create(jwkSetUri.toURL(), (url) -> fetchJwkSet(jwkSetUri))
                .refreshAheadCache(false)
                .build();
        }
        catch (java.net.MalformedURLException ex) {
            throw new IllegalStateException("Invalid JWK Set location: " + jwkSetUri, ex);
        }
        JWSKeySelector<SecurityContext> keySelector = new JWSVerificationKeySelector<>(this.acceptedAlgorithms,
                jwkSource);
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector((header, context) -> keySelector.selectJWSKeys(header, context)
            .stream()
            .filter(this::hasAcceptableSize)
            .toList());
        // typ header and claims are validated by this class
        processor.setJWSTypeVerifier((type, context) -> {
        });
        processor.setJWTClaimsSetVerifier((claims, context) -> {
        });
        return processor;
    }

    private Resource fetchJwkSet(URI jwkSetUri) throws java.io.IOException {
        SsfHttpResponse response = this.httpClient.execute(SsfHttpRequest.get(jwkSetUri));
        if (!response.isSuccessful()) {
            throw new java.io.IOException("The JWK Set endpoint answered with status " + response.status());
        }
        return new Resource(response.body(), response.header("Content-Type"));
    }

    private boolean hasAcceptableSize(Key key) {
        return !(key instanceof RSAPublicKey rsaKey) || rsaKey.getModulus().bitLength() >= this.minRsaKeySize;
    }

}
