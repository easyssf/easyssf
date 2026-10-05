package org.easyssf.core.caep;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfEventTimestamps;
import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.support.SsfAssert;
import org.easyssf.core.support.SsfCollections;

/**
 * A CAEP 1.0 event of a SET, with typed access to the claims every CAEP event may carry
 * (section 2: {@link #eventTimestamp()}, {@link #initiatingEntity()},
 * {@link #reasonAdmin()}, {@link #reasonUser()}) and to the event-specific claims of
 * section 3. An accessor returns {@code null} (or an empty collection) when the claim is
 * absent, whether because the transmitter left it out or because the {@link #kind()} does
 * not define it. The {@link #payload()} is the event as received, for anything else.
 *
 * <p>
 * The subject of the event is the {@code sub_id} of the SET,
 * {@code SsfEventContext.subject()} in a handler: CAEP does not prescribe a subject
 * format.
 *
 * @param eventType the event type URI
 * @param kind the kind of event, {@link SsfCaepEventKind#OTHER} for a CAEP event type
 * this version does not know
 * @param payload the event payload as received
 */
public record SsfCaepEvent(String eventType, SsfCaepEventKind kind, Map<String, Object> payload) {

    public SsfCaepEvent {
        SsfAssert.notNull(eventType, "eventType must not be null");
        SsfAssert.notNull(kind, "kind must not be null");
        payload = SsfCollections.copyOf((payload != null) ? payload : Map.of());
    }

    /**
     * @param eventType the event type URI or alias
     * @param payload the event payload, may be {@code null}
     * @return the event, {@code null} if the event type is not a CAEP event
     */
    public static SsfCaepEvent of(String eventType, Map<String, Object> payload) {
        SsfCaepEventKind kind = SsfCaepEventKind.of(eventType);
        if (kind == null) {
            return null;
        }
        return new SsfCaepEvent(SsfEventTypes.resolve(eventType), kind, payload);
    }

    /**
     * The CAEP events of a SET, in the order of its {@code events} claim. CAEP allows one
     * event per SET; a SET with several is read like one of each.
     * @param eventToken a verified SET
     * @return the CAEP events, empty if the SET carries none
     */
    @SuppressWarnings("unchecked")
    public static List<SsfCaepEvent> in(SsfEventToken eventToken) {
        SsfAssert.notNull(eventToken, "eventToken must not be null");
        List<SsfCaepEvent> events = new ArrayList<>();
        for (Map.Entry<String, Object> entry : eventToken.events().entrySet()) {
            SsfCaepEventKind kind = SsfCaepEventKind.of(entry.getKey());
            if (kind != null) {
                Map<String, Object> payload = (entry.getValue() instanceof Map<?, ?> map) ? (Map<String, Object>) map
                        : Map.of();
                events.add(new SsfCaepEvent(entry.getKey(), kind, payload));
            }
        }
        return List.copyOf(events);
    }

    // CAEP section 2: claims every event may carry

    /**
     * @return the {@code event_timestamp}, the time the event occurred, {@code null} if
     * absent. CAEP defines seconds since the epoch; a value that is clearly milliseconds
     * is accepted too.
     */
    public Instant eventTimestamp() {
        return SsfEventTimestamps.from(this.payload.get("event_timestamp"));
    }

    /**
     * @return the {@code initiating_entity}, who invoked the event: {@code admin},
     * {@code user}, {@code policy} or {@code system}; {@code null} if absent
     */
    public String initiatingEntity() {
        return string("initiating_entity");
    }

    /**
     * @return the {@code reason_admin}, the reason for administrators, by BCP 47 language
     * tag ({@code en}, {@code de-CH}, ...); empty if absent. A transmitter that sends a
     * plain string instead of the localizable object of CAEP gets the key {@code ""}.
     */
    public Map<String, String> reasonAdmin() {
        return localized("reason_admin");
    }

    /**
     * @return the {@code reason_user}, the reason for display to the end user, by BCP 47
     * language tag; empty if absent, a plain string under the key {@code ""}
     */
    public Map<String, String> reasonUser() {
        return localized("reason_user");
    }

    // CAEP section 3: event-specific claims

    /**
     * @return the {@code claims} of a {@link SsfCaepEventKind#TOKEN_CLAIMS_CHANGE}, each
     * with its new value; {@code null} if absent
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> claims() {
        return (this.payload.get("claims") instanceof Map<?, ?> claims) ? (Map<String, Object>) claims : null;
    }

    /**
     * @return the {@code credential_type} of a
     * {@link SsfCaepEventKind#CREDENTIAL_CHANGE}: {@code password}, {@code pin},
     * {@code x509}, {@code fido2-platform}, {@code fido2-roaming}, {@code fido-u2f},
     * {@code verifiable-credential}, {@code phone-voice}, {@code phone-sms}, {@code app}
     * or a value agreed with the transmitter; {@code null} if absent
     */
    public String credentialType() {
        return string("credential_type");
    }

    /**
     * @return the {@code change_type} of a {@link SsfCaepEventKind#CREDENTIAL_CHANGE}:
     * {@code create}, {@code revoke}, {@code update} or {@code delete}; {@code null} if
     * absent
     */
    public String changeType() {
        return string("change_type");
    }

    /**
     * @return the {@code friendly_name} of the credential of a
     * {@link SsfCaepEventKind#CREDENTIAL_CHANGE}, {@code null} if absent
     */
    public String friendlyName() {
        return string("friendly_name");
    }

    /**
     * @return the {@code x509_issuer} of an X.509 credential, {@code null} if absent
     */
    public String x509Issuer() {
        return string("x509_issuer");
    }

    /**
     * @return the {@code x509_serial} of an X.509 credential, {@code null} if absent
     */
    public String x509Serial() {
        return string("x509_serial");
    }

    /**
     * @return the {@code fido2_aaguid}, the FIDO2 Authenticator Attestation GUID of the
     * credential, {@code null} if absent
     */
    public String fido2Aaguid() {
        return string("fido2_aaguid");
    }

    /**
     * @return the {@code namespace} of the levels of an
     * {@link SsfCaepEventKind#ASSURANCE_LEVEL_CHANGE}: {@code RFC8176}, {@code RFC6711},
     * {@code ISO-IEC-29115}, {@code NIST-IAL}, {@code NIST-AAL}, {@code NIST-FAL} or a
     * value agreed with the transmitter; {@code null} if absent
     */
    public String namespace() {
        return string("namespace");
    }

    /**
     * @return the {@code current_level}: the assurance level of an
     * {@link SsfCaepEventKind#ASSURANCE_LEVEL_CHANGE} in its {@link #namespace()}, or the
     * risk level of a {@link SsfCaepEventKind#RISK_LEVEL_CHANGE} ({@code LOW},
     * {@code MEDIUM}, {@code HIGH}); {@code null} if absent
     */
    public String currentLevel() {
        return string("current_level");
    }

    /**
     * @return the {@code previous_level} of an assurance or risk level change,
     * {@code null} if absent: the transmitter does not know the previous level
     */
    public String previousLevel() {
        return string("previous_level");
    }

    /**
     * @return the {@code change_direction} of an
     * {@link SsfCaepEventKind#ASSURANCE_LEVEL_CHANGE}: {@code increase} or
     * {@code decrease}; {@code null} if absent
     */
    public String changeDirection() {
        return string("change_direction");
    }

    /**
     * @return the {@code current_status} of a
     * {@link SsfCaepEventKind#DEVICE_COMPLIANCE_CHANGE}: {@code compliant} or
     * {@code not-compliant}; {@code null} if absent
     */
    public String currentStatus() {
        return string("current_status");
    }

    /**
     * @return the {@code previous_status} of a
     * {@link SsfCaepEventKind#DEVICE_COMPLIANCE_CHANGE}: {@code compliant} or
     * {@code not-compliant}; {@code null} if absent
     */
    public String previousStatus() {
        return string("previous_status");
    }

    /**
     * @return the {@code fp_ua}, the fingerprint of the user agent of a
     * {@link SsfCaepEventKind#SESSION_ESTABLISHED} or
     * {@link SsfCaepEventKind#SESSION_PRESENTED}; {@code null} if absent
     */
    public String fpUa() {
        return string("fp_ua");
    }

    /**
     * @return the {@code acr}, the authentication context class reference of a
     * {@link SsfCaepEventKind#SESSION_ESTABLISHED}, as in an ID Token; {@code null} if
     * absent
     */
    public String acr() {
        return string("acr");
    }

    /**
     * @return the {@code amr}, the authentication methods references of a
     * {@link SsfCaepEventKind#SESSION_ESTABLISHED}, as in an ID Token; empty if absent
     */
    public List<String> amr() {
        if (!(this.payload.get("amr") instanceof List<?> amr)) {
            return List.of();
        }
        return amr.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    /**
     * @return the {@code ext_id}, the external session identifier of a
     * {@link SsfCaepEventKind#SESSION_ESTABLISHED} or
     * {@link SsfCaepEventKind#SESSION_PRESENTED} that correlates it with a broader
     * session, for example a SAML one; {@code null} if absent
     */
    public String extId() {
        return string("ext_id");
    }

    /**
     * @return the {@code principal} of a {@link SsfCaepEventKind#RISK_LEVEL_CHANGE}, the
     * entity whose risk changed: {@code USER}, {@code DEVICE}, {@code SESSION},
     * {@code TENANT}, {@code ORG_UNIT}, {@code GROUP} or another entity of SSF;
     * {@code null} if absent
     */
    public String principal() {
        return string("principal");
    }

    /**
     * @return the {@code risk_reason} of a {@link SsfCaepEventKind#RISK_LEVEL_CHANGE},
     * {@code null} if absent
     */
    public String riskReason() {
        return string("risk_reason");
    }

    private String string(String name) {
        Object value = this.payload.get(name);
        return (value != null) ? value.toString() : null;
    }

    private Map<String, String> localized(String name) {
        Object value = this.payload.get(name);
        if (value instanceof Map<?, ?> byLanguage) {
            Map<String, String> reasons = new LinkedHashMap<>();
            byLanguage.forEach((language, text) -> {
                if (text != null) {
                    reasons.put(String.valueOf(language), text.toString());
                }
            });
            return Map.copyOf(reasons);
        }
        return (value != null) ? Map.of("", value.toString()) : Map.of();
    }

}
