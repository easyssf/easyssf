package org.easyssf.core.event;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event type URIs defined by OpenID SSF, CAEP and RISC, together with short aliases (for
 * example {@code CaepSessionRevoked}) that can be used wherever an event type is
 * expected.
 */
public final class SsfEventTypes {

    private static final String SSF = "https://schemas.openid.net/secevent/ssf/event-type/";

    private static final String CAEP = "https://schemas.openid.net/secevent/caep/event-type/";

    private static final String RISC = "https://schemas.openid.net/secevent/risc/event-type/";

    public static final String SSF_STREAM_VERIFICATION = SSF + "verification";

    public static final String SSF_STREAM_UPDATED = SSF + "stream-updated";

    public static final String CAEP_SESSION_REVOKED = CAEP + "session-revoked";

    public static final String CAEP_TOKEN_CLAIMS_CHANGE = CAEP + "token-claims-change";

    public static final String CAEP_CREDENTIAL_CHANGE = CAEP + "credential-change";

    public static final String CAEP_ASSURANCE_LEVEL_CHANGE = CAEP + "assurance-level-change";

    public static final String CAEP_DEVICE_COMPLIANCE_CHANGE = CAEP + "device-compliance-change";

    public static final String CAEP_SESSION_ESTABLISHED = CAEP + "session-established";

    public static final String CAEP_SESSION_PRESENTED = CAEP + "session-presented";

    public static final String CAEP_RISK_LEVEL_CHANGE = CAEP + "risk-level-change";

    public static final String RISC_ACCOUNT_CREDENTIAL_CHANGE_REQUIRED = RISC + "account-credential-change-required";

    public static final String RISC_ACCOUNT_PURGED = RISC + "account-purged";

    public static final String RISC_ACCOUNT_DISABLED = RISC + "account-disabled";

    public static final String RISC_ACCOUNT_ENABLED = RISC + "account-enabled";

    public static final String RISC_IDENTIFIER_CHANGED = RISC + "identifier-changed";

    public static final String RISC_IDENTIFIER_RECYCLED = RISC + "identifier-recycled";

    public static final String RISC_CREDENTIAL_COMPROMISE = RISC + "credential-compromise";

    public static final String RISC_OPT_IN = RISC + "opt-in";

    public static final String RISC_OPT_OUT_INITIATED = RISC + "opt-out-initiated";

    public static final String RISC_OPT_OUT_CANCELLED = RISC + "opt-out-cancelled";

    public static final String RISC_OPT_OUT_EFFECTIVE = RISC + "opt-out-effective";

    public static final String RISC_RECOVERY_ACTIVATED = RISC + "recovery-activated";

    public static final String RISC_RECOVERY_INFORMATION_CHANGED = RISC + "recovery-information-changed";

    private static final Map<String, String> URI_BY_ALIAS = new ConcurrentHashMap<>();

    private static final Map<String, String> ALIAS_BY_URI = new ConcurrentHashMap<>();

    static {
        register("SsfStreamVerification", SSF_STREAM_VERIFICATION);
        register("SsfStreamUpdated", SSF_STREAM_UPDATED);
        register("CaepSessionRevoked", CAEP_SESSION_REVOKED);
        register("CaepTokenClaimsChange", CAEP_TOKEN_CLAIMS_CHANGE);
        register("CaepCredentialChange", CAEP_CREDENTIAL_CHANGE);
        register("CaepAssuranceLevelChange", CAEP_ASSURANCE_LEVEL_CHANGE);
        register("CaepDeviceComplianceChange", CAEP_DEVICE_COMPLIANCE_CHANGE);
        register("CaepSessionEstablished", CAEP_SESSION_ESTABLISHED);
        register("CaepSessionPresented", CAEP_SESSION_PRESENTED);
        register("CaepRiskLevelChange", CAEP_RISK_LEVEL_CHANGE);
        register("RiscAccountCredentialChangeRequired", RISC_ACCOUNT_CREDENTIAL_CHANGE_REQUIRED);
        register("RiscAccountPurged", RISC_ACCOUNT_PURGED);
        register("RiscAccountDisabled", RISC_ACCOUNT_DISABLED);
        register("RiscAccountEnabled", RISC_ACCOUNT_ENABLED);
        register("RiscIdentifierChanged", RISC_IDENTIFIER_CHANGED);
        register("RiscIdentifierRecycled", RISC_IDENTIFIER_RECYCLED);
        register("RiscCredentialCompromise", RISC_CREDENTIAL_COMPROMISE);
        register("RiscOptIn", RISC_OPT_IN);
        register("RiscOptOutInitiated", RISC_OPT_OUT_INITIATED);
        register("RiscOptOutCancelled", RISC_OPT_OUT_CANCELLED);
        register("RiscOptOutEffective", RISC_OPT_OUT_EFFECTIVE);
        register("RiscRecoveryActivated", RISC_RECOVERY_ACTIVATED);
        register("RiscRecoveryInformationChanged", RISC_RECOVERY_INFORMATION_CHANGED);
    }

    private SsfEventTypes() {
    }

    private static void register(String alias, String uri) {
        URI_BY_ALIAS.put(alias, uri);
        ALIAS_BY_URI.put(uri, alias);
    }

    /**
     * Registers an alias for an event type URI, for example of a vendor specific event
     * type, so that it can be used wherever an event type is named: in handlers, in the
     * events requested of a stream, in configuration. Aliases are a convenience of
     * easyssf, the URIs stay the canonical names.
     *
     * <p>
     * Aliases do not conflict: an alias cannot be redefined to another URI, neither a
     * built-in one nor one registered before. A URI may have several aliases; the first
     * one registered is the one {@link #aliasOf(String)} returns. Registering a mapping
     * that exists already does nothing.
     * @param alias the alias, a name without {@code :} or {@code /} so that it cannot be
     * mistaken for a URI
     * @param uri the absolute event type URI
     * @throws IllegalArgumentException if the alias or the URI is not well-formed, or the
     * alias is already mapped to another URI
     */
    public static synchronized void registerAlias(String alias, String uri) {
        if (alias == null || alias.isBlank() || alias.contains(":") || alias.contains("/")
                || !alias.equals(alias.strip())) {
            throw new IllegalArgumentException(
                    "An event type alias must be a name without ':' or '/' and surrounding whitespace: '" + alias
                            + "'");
        }
        if (uri == null || uri.isBlank() || !uri.contains(":") || !uri.equals(uri.strip())) {
            throw new IllegalArgumentException("An event type must be an absolute URI: '" + uri + "'");
        }
        String existing = URI_BY_ALIAS.get(alias);
        if (existing != null && !existing.equals(uri)) {
            throw new IllegalArgumentException(
                    "The event type alias '" + alias + "' is already mapped to " + existing + ", not to " + uri);
        }
        URI_BY_ALIAS.put(alias, uri);
        ALIAS_BY_URI.putIfAbsent(uri, alias);
    }

    /**
     * @return the known aliases and their event type URIs, built-in and registered
     */
    public static Map<String, String> aliases() {
        return Collections.unmodifiableMap(new TreeMap<>(URI_BY_ALIAS));
    }

    /**
     * @return whether the name is a known alias
     */
    public static boolean isAlias(String name) {
        return name != null && URI_BY_ALIAS.containsKey(name);
    }

    /**
     * Resolves an alias to its event type URI. Anything that is not a known alias is
     * returned unchanged, so full URIs (including vendor specific ones) pass through.
     */
    public static String resolve(String aliasOrUri) {
        return URI_BY_ALIAS.getOrDefault(aliasOrUri, aliasOrUri);
    }

    /**
     * Returns the alias of the given event type URI, or the URI itself if it has none.
     */
    public static String aliasOf(String eventTypeUri) {
        return ALIAS_BY_URI.getOrDefault(eventTypeUri, eventTypeUri);
    }

}
