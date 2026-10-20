package org.easyssf.test.conformance;

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

    /**
     * The suite release the default images are: release-v5.3.2 of 2026-10-09, the first
     * release with the SSF receiver tests as they are on master (the poll race of
     * release-v5.3.1 included), pinned by the digest of its multi-platform image. The
     * single place to change the images.
     */
    static final String DEFAULT_SUITE_IMAGE = "registry.gitlab.com/openid/conformance-suite@sha256:c795df1b151e0c2f2e40f17a2470991cc38bc91628449e9bb090e2372fe8b822";

    static final String DEFAULT_SUITE_NGINX_IMAGE = "registry.gitlab.com/openid/conformance-suite/nginx@sha256:607458218abb58204201e4d283af90177d6b542535a99a21041d399a1505a6a0";

    /**
     * The suite's own compose file runs MongoDB 6.0.13, as the suite does in production.
     * For the tests here a current release, with far fewer known vulnerabilities, works
     * just as well: mongo:9.0.4, pinned by digest like the suite images. Not older:
     * MongoDB 8.0 and newer refuse to start on a Linux kernel from 6.19 up to 7.0.13 (a
     * tcmalloc incompatibility, SERVER-121912), and before 9.0.4 they took Ubuntu's 7.0
     * kernels, the ones of GitHub's runners since October 2026, for 7.0.0
     * (SERVER-131779).
     */
    static final String DEFAULT_SUITE_MONGODB_IMAGE = "mongo@sha256:dc28ed5a9d0fc616903c3f463bb1e53b92b49f441721b91926e87d90bf957ce8";

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
