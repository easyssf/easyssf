package org.easyssf.test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.easyssf.core.event.SsfEventTypes;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONArrayUtils;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A minimal SSF transmitter / identity provider for tests: publishes transmitter metadata
 * and a JWK Set over HTTP, signs SETs and access tokens, issues access tokens for the
 * client credentials grant and offers stream management and a poll endpoint that behave
 * like the ones of Keycloak.
 */
public final class TestTransmitter implements AutoCloseable {

    public static final String AUDIENCE = "https://receiver.example";

    public static final String CLIENT_ID = "receiver";

    public static final String CLIENT_SECRET = "receiver-secret";

    public static final String ACCESS_TOKEN = "transmitter-access-token";

    private final Map<String, String> documents = new ConcurrentHashMap<>();

    private final List<Map<String, Object>> streams = new CopyOnWriteArrayList<>();

    /**
     * guarded by queueLock; a lock rather than synchronized, which pins virtual threads
     * before JDK 24
     */
    private final Map<String, String> queuedSets = new LinkedHashMap<>();

    private final ReentrantLock queueLock = new ReentrantLock();

    private final Condition setQueued = this.queueLock.newCondition();

    private final List<String> acknowledgedSets = new CopyOnWriteArrayList<>();

    private final Map<String, Object> reportedErrors = new ConcurrentHashMap<>();

    private final List<String> verificationRequests = new CopyOnWriteArrayList<>();

    private final List<Map<String, Object>> removedSubjects = new CopyOnWriteArrayList<>();

    private final List<Map<String, Object>> addedSubjects = new CopyOnWriteArrayList<>();

    private final AtomicInteger tokenRequests = new AtomicInteger();

    private final AtomicInteger pollRequests = new AtomicInteger();

    private volatile Map<String, Object> lastPollRequest = Map.of();

    private volatile Duration longPollHold = Duration.ZERO;

    private volatile boolean available = true;

    private volatile String validAccessToken = ACCESS_TOKEN;

    private volatile String streamIssuer;

    private volatile String lastUserAgent;

    private final HttpServer server;

    private final RSAKey key;

    private final String issuer;

    public TestTransmitter() {
        this(MetadataLocation.SSF);
    }

    public TestTransmitter(MetadataLocation metadataLocation) {
        try {
            this.key = generateKey(2048, "test-key");
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            // one thread per request, so that a held poll request does not block the
            // others
            this.server.setExecutor(
                    Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("test-transmitter-", 0).factory()));
            this.server.createContext("/", (exchange) -> {
                try {
                    handle(exchange);
                }
                catch (Exception ex) {
                    respond(exchange, 500, JSONObjectUtils.toJSONString(Map.of("error", String.valueOf(ex))));
                }
            });
            this.server.start();
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        String base = "http://127.0.0.1:" + this.server.getAddress().getPort();
        this.issuer = base + "/realms/test";
        publishJwks(this.key);
        publishMetadata(metadataLocation);
    }

    private void handle(HttpExchange exchange) throws Exception {
        String path = exchange.getRequestURI().getPath();
        this.lastUserAgent = exchange.getRequestHeaders().getFirst("User-Agent");
        String method = exchange.getRequestMethod();
        String document = this.documents.get(path);
        if (document != null) {
            respond(exchange, 200, document);
            return;
        }
        if (!path.startsWith("/realms/test/")) {
            respond(exchange, 404, null);
            return;
        }
        if (!this.available) {
            respond(exchange, 503, null);
            return;
        }
        String endpoint = path.substring("/realms/test/".length());
        if (endpoint.equals("token")) {
            String credentials = Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean authenticated = ("Basic " + credentials)
                .equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    || (form.contains("client_id=" + CLIENT_ID) && form.contains("client_secret=" + CLIENT_SECRET));
            if (!authenticated || !form.contains("grant_type=client_credentials")) {
                respond(exchange, 401, JSONObjectUtils.toJSONString(Map.of("error", "invalid_client")));
                return;
            }
            this.tokenRequests.incrementAndGet();
            respond(exchange, 200, JSONObjectUtils.toJSONString(
                    Map.of("access_token", this.validAccessToken, "token_type", "Bearer", "expires_in", 300)));
            return;
        }
        if (!("Bearer " + this.validAccessToken).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, 401, null);
            return;
        }
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> request = requestBody.isBlank() ? Map.of() : JSONObjectUtils.parse(requestBody);
        String query = exchange.getRequestURI().getQuery();
        String streamId = (query != null && query.startsWith("stream_id=")) ? query.substring("stream_id=".length())
                : (String) request.get("stream_id");
        switch (endpoint) {
            case "streams" -> handleStreams(exchange, method, streamId, request);
            case "streams/status" -> respond(exchange, (findStream(streamId) != null) ? 200 : 404,
                    JSONObjectUtils.toJSONString(Map.of("stream_id", String.valueOf(streamId), "status",
                            String.valueOf(request.getOrDefault("status", "enabled")))));
            case "verify" -> {
                this.verificationRequests.add(String.valueOf(request.get("state")));
                respond(exchange, 204, null);
            }
            case "poll" -> respond(exchange, 200, JSONObjectUtils.toJSONString(poll(request)));
            case "subjects/add" -> {
                this.addedSubjects.add(subject(request));
                respond(exchange, 200, null);
            }
            case "subjects/remove" -> {
                this.removedSubjects.add(subject(request));
                respond(exchange, 204, null);
            }
            default -> respond(exchange, 404, null);
        }
    }

    private void handleStreams(HttpExchange exchange, String method, String streamId, Map<String, Object> request)
            throws IOException {
        Map<String, Object> stream = findStream(streamId);
        switch (method) {
            case "GET" -> {
                if (streamId == null) {
                    respond(exchange, 200, JSONArrayUtils.toJSONString(List.copyOf(this.streams)));
                }
                else {
                    respond(exchange, (stream != null) ? 200 : 404,
                            (stream != null) ? JSONObjectUtils.toJSONString(stream) : null);
                }
            }
            case "POST" -> {
                if (!this.streams.isEmpty()) {
                    respond(exchange, 409, JSONObjectUtils.toJSONString(Map.of("error", "stream_error",
                            "error_description", "Only one stream per receiver is allowed")));
                    return;
                }
                respond(exchange, 201, JSONObjectUtils.toJSONString(addStream(request)));
            }
            case "PATCH", "PUT" -> {
                if (stream == null) {
                    respond(exchange, 404, null);
                    return;
                }
                Map<String, Object> updated = new LinkedHashMap<>(stream);
                updated.putAll(request);
                this.streams.remove(stream);
                respond(exchange, 200, JSONObjectUtils.toJSONString(addStream(updated)));
            }
            case "DELETE" -> {
                this.streams.remove(stream);
                respond(exchange, (stream != null) ? 204 : 404, null);
            }
            default -> respond(exchange, 405, null);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> poll(Map<String, Object> request) throws InterruptedException {
        this.pollRequests.incrementAndGet();
        this.lastPollRequest = Map.copyOf(request);
        this.queueLock.lock();
        try {
            if (request.get("ack") instanceof List<?> acks) {
                acks.forEach((jti) -> {
                    if (this.queuedSets.remove(jti) != null) {
                        this.acknowledgedSets.add((String) jti);
                    }
                });
            }
            if (request.get("setErrs") instanceof Map<?, ?> errors) {
                ((Map<String, Object>) errors).forEach((jti, error) -> {
                    this.queuedSets.remove(jti);
                    this.reportedErrors.put(jti, error);
                });
            }
            int maxEvents = ((Number) request.getOrDefault("maxEvents", 10)).intValue();
            // a long poll (returnImmediately false or absent, RFC 8936 section 2.5) is
            // held until a
            // SET is queued or the hold time elapses
            if (!Boolean.TRUE.equals(request.get("returnImmediately")) && maxEvents > 0) {
                long remaining = this.longPollHold.toNanos();
                while (this.queuedSets.isEmpty() && remaining > 0) {
                    remaining = this.setQueued.awaitNanos(remaining);
                }
            }
            Map<String, Object> sets = new LinkedHashMap<>();
            this.queuedSets.entrySet()
                .stream()
                .limit(maxEvents)
                .forEach((set) -> sets.put(set.getKey(), set.getValue()));
            return Map.of("sets", sets, "moreAvailable", this.queuedSets.size() > sets.size());
        }
        finally {
            this.queueLock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> subject(Map<String, Object> request) {
        return (request.get("subject") instanceof Map<?, ?> subject) ? (Map<String, Object>) subject : Map.of();
    }

    private Map<String, Object> findStream(String streamId) {
        return this.streams.stream()
            .filter((stream) -> stream.get("stream_id").equals(streamId))
            .findFirst()
            .orElse(null);
    }

    /**
     * Registers a stream the way the transmitter does for a create request: assigns the
     * identifier, the audience and, for POLL delivery, the poll endpoint.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> addStream(Map<String, Object> request) {
        Map<String, Object> stream = new LinkedHashMap<>(request);
        String streamId = (String) stream.computeIfAbsent("stream_id", (key) -> UUID.randomUUID().toString());
        stream.put("iss", (this.streamIssuer != null) ? this.streamIssuer : this.issuer);
        stream.put("aud", List.of(CLIENT_ID + "/" + streamId));
        stream.put("events_delivered", stream.getOrDefault("events_requested", List.of()));
        Map<String, Object> delivery = new LinkedHashMap<>((Map<String, Object>) stream.get("delivery"));
        delivery.remove("authorization_header");
        if ("urn:ietf:rfc:8936".equals(delivery.get("method"))) {
            delivery.put("endpoint_url", pollUri());
        }
        stream.put("delivery", delivery);
        this.streams.add(stream);
        return stream;
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = (json != null) ? json.getBytes(StandardCharsets.UTF_8) : new byte[0];
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, (body.length > 0) ? body.length : -1);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    /**
     * Makes the token, stream management and poll endpoints answer with 503.
     */
    public void setAvailable(boolean available) {
        this.available = available;
    }

    /**
     * The User-Agent header of the most recent request.
     */
    public String lastUserAgent() {
        return this.lastUserAgent;
    }

    /**
     * Makes the access tokens issued so far invalid: requests with them are answered with
     * 401 and the token endpoint issues another token.
     */
    public void expireAccessTokens() {
        this.validAccessToken = ACCESS_TOKEN + "-" + UUID.randomUUID();
    }

    /**
     * Makes the transmitter create streams with the given issuer instead of its own.
     */
    public void setStreamIssuer(String streamIssuer) {
        this.streamIssuer = streamIssuer;
    }

    public String tokenUri() {
        return this.issuer + "/token";
    }

    public String pollUri() {
        return this.issuer + "/poll";
    }

    public int tokenRequests() {
        return this.tokenRequests.get();
    }

    public List<Map<String, Object>> streams() {
        return this.streams;
    }

    /**
     * Makes the given SET available at the poll endpoint.
     */
    public void queueSet(String set) {
        try {
            queueSet(SignedJWT.parse(set).getJWTClaimsSet().getJWTID(), set);
        }
        catch (java.text.ParseException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    public void queueSet(String jti, String set) {
        this.queueLock.lock();
        try {
            this.queuedSets.put(jti, set);
            // wakes up a held poll request
            this.setQueued.signalAll();
        }
        finally {
            this.queueLock.unlock();
        }
    }

    /**
     * How long a poll request without {@code returnImmediately: true} is held while no
     * SET is queued. Zero by default: the transmitter then answers at once, as if it did
     * not support long polling.
     */
    public void setLongPollHold(Duration longPollHold) {
        this.longPollHold = (longPollHold != null) ? longPollHold : Duration.ZERO;
    }

    /**
     * @return the number of SETs queued and not yet acknowledged
     */
    public int queuedSetCount() {
        this.queueLock.lock();
        try {
            return this.queuedSets.size();
        }
        finally {
            this.queueLock.unlock();
        }
    }

    /**
     * @return the number of poll requests received
     */
    public int pollRequests() {
        return this.pollRequests.get();
    }

    /**
     * @return the body of the last poll request, empty if none was received
     */
    public Map<String, Object> lastPollRequest() {
        return this.lastPollRequest;
    }

    public List<String> acknowledgedSets() {
        return this.acknowledgedSets;
    }

    public Map<String, Object> reportedErrors() {
        return this.reportedErrors;
    }

    public List<String> verificationRequests() {
        return this.verificationRequests;
    }

    public List<Map<String, Object>> addedSubjects() {
        return this.addedSubjects;
    }

    public List<Map<String, Object>> removedSubjects() {
        return this.removedSubjects;
    }

    /**
     * Forgets streams, queued SETs and what the receiver reported.
     */
    public void reset() {
        this.streams.clear();
        this.queueLock.lock();
        try {
            this.queuedSets.clear();
            this.setQueued.signalAll();
        }
        finally {
            this.queueLock.unlock();
        }
        this.pollRequests.set(0);
        this.lastPollRequest = Map.of();
        this.longPollHold = Duration.ZERO;
        this.acknowledgedSets.clear();
        this.reportedErrors.clear();
        this.verificationRequests.clear();
        this.addedSubjects.clear();
        this.removedSubjects.clear();
        this.available = true;
        this.validAccessToken = ACCESS_TOKEN;
        this.streamIssuer = null;
    }

    public void publishMetadata(MetadataLocation metadataLocation) {
        String metadata = JSONObjectUtils.toJSONString(Map.of("issuer", this.issuer, "jwks_uri", jwksUri(),
                "configuration_endpoint", this.issuer + "/streams", "status_endpoint", this.issuer + "/streams/status",
                "verification_endpoint", this.issuer + "/verify", "add_subject_endpoint", this.issuer + "/subjects/add",
                "remove_subject_endpoint", this.issuer + "/subjects/remove"));
        switch (metadataLocation) {
            case SSF -> this.documents.put("/.well-known/ssf-configuration/realms/test", metadata);
            case OIDC_STYLE -> this.documents.put("/realms/test/.well-known/ssf-configuration", metadata);
            case NONE -> {
            }
        }
    }

    public enum MetadataLocation {

        SSF, OIDC_STYLE, NONE

    }

    public static RSAKey generateKey(int bits, String keyId) {
        try {
            return new RSAKeyGenerator(bits, true).keyID(keyId).generate();
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public void publishJwks(RSAKey... keys) {
        this.documents.put("/realms/test/jwks", new JWKSet(List.of(keys)).toString(true));
    }

    public String issuer() {
        return this.issuer;
    }

    public String jwksUri() {
        return this.issuer + "/jwks";
    }

    public RSAKey key() {
        return this.key;
    }

    /**
     * Claims of a valid SET with a single event.
     */
    public JWTClaimsSet.Builder setClaims(String eventType, Map<String, Object> subjectId) {
        return setClaims(eventType, subjectId, Map.of("event_timestamp", Instant.now().getEpochSecond()));
    }

    public JWTClaimsSet.Builder setClaims(String eventType, Map<String, Object> subjectId, Map<String, Object> event) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(this.issuer)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(new Date())
            .audience(AUDIENCE)
            .claim("events", Map.of(SsfEventTypes.resolve(eventType), event));
        if (subjectId != null) {
            claims.claim("sub_id", subjectId);
        }
        return claims;
    }

    /**
     * A valid, signed SET with a single event.
     */
    public String set(String eventType, Map<String, Object> subjectId) {
        return signSet(setClaims(eventType, subjectId).build());
    }

    public String signSet(JWTClaimsSet claims) {
        return sign(this.key, new JOSEObjectType("secevent+jwt"), claims);
    }

    public String accessToken(String subject, String sessionId, Instant issuedAt) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(this.issuer)
            .subject(subject)
            .claim("sid", sessionId)
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .build();
        return sign(this.key, JOSEObjectType.JWT, claims);
    }

    public static String sign(RSAKey key, JOSEObjectType type, JWTClaimsSet claims) {
        return sign(key, JWSAlgorithm.RS256, type, claims);
    }

    public static String sign(RSAKey key, JWSAlgorithm algorithm, JOSEObjectType type, JWTClaimsSet claims) {
        try {
            JWSHeader header = new JWSHeader.Builder(algorithm).type(type).keyID(key.getKeyID()).build();
            SignedJWT jwt = new SignedJWT(header, claims);
            // allow weak keys, tests need to sign with keys the receiver has to reject
            jwt.sign(new RSASSASigner(key.toPrivateKey(), true));
            return jwt.serialize();
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public void close() {
        this.server.stop(0);
    }

}
