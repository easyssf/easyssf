package org.easyssf.conformance;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/**
 * How the conformance tests start the suite and expose the system under test, taken from
 * system properties ({@code -Dcts.suite.image=...}) or environment variables
 * ({@code CTS_SUITE_IMAGE}).
 */
public final class ConformanceSettings {

    static final String SUITE_IMAGE = "cts.suite.image";

    static final String SUITE_NGINX_IMAGE = "cts.suite.nginx-image";

    static final String SUITE_MONGODB_IMAGE = "cts.suite.mongodb-image";

    /**
     * The host port the suite listens on. Fixed, as the suite has to know its own URL
     * before it starts.
     */
    static final String SUITE_PORT = "cts.suite.port";

    /**
     * The port the receiver under test listens on. Fixed, as the suite reaches it by its
     * push URL.
     */
    static final String RECEIVER_PORT = "cts.receiver.port";

    /**
     * The push URL the suite is to deliver to, when the default of
     * {@code https://<host as seen from the suite>:<receiver port>/ssf/push} does not
     * fit.
     */
    static final String RECEIVER_PUSH_URL = "cts.receiver.push-url";

    static final String DEFAULT_SUITE_VERSION = "release-v5.3.1";

    static final String DEFAULT_SUITE_IMAGE = "registry.gitlab.com/openid/conformance-suite:" + DEFAULT_SUITE_VERSION;

    static final String DEFAULT_SUITE_NGINX_IMAGE = "registry.gitlab.com/openid/conformance-suite/nginx:"
            + DEFAULT_SUITE_VERSION;

    static final String DEFAULT_SUITE_MONGODB_IMAGE = "mongo:6.0.13";

    static final int DEFAULT_SUITE_PORT = 18443;

    static final int DEFAULT_RECEIVER_PORT = 9444;

    private ConformanceSettings() {
    }

    public static String suiteImage() {
        return get(SUITE_IMAGE).orElse(DEFAULT_SUITE_IMAGE);
    }

    public static String suiteNginxImage() {
        return get(SUITE_NGINX_IMAGE).orElse(DEFAULT_SUITE_NGINX_IMAGE);
    }

    public static String suiteMongoDbImage() {
        return get(SUITE_MONGODB_IMAGE).orElse(DEFAULT_SUITE_MONGODB_IMAGE);
    }

    public static int suitePort() {
        return get(SUITE_PORT).map(Integer::parseInt).orElse(DEFAULT_SUITE_PORT);
    }

    public static int receiverPort() {
        return get(RECEIVER_PORT).map(Integer::parseInt).orElse(DEFAULT_RECEIVER_PORT);
    }

    public static Optional<URI> receiverPushUrl() {
        return get(RECEIVER_PUSH_URL).map(URI::create);
    }

    static Optional<String> get(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(property.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
        }
        return (value != null && !value.isBlank()) ? Optional.of(value.trim()) : Optional.empty();
    }

}
