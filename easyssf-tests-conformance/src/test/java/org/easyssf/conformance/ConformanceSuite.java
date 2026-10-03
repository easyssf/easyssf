package org.easyssf.conformance;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

/**
 * The OpenID conformance suite the tests run against, started with Testcontainers like
 * {@code docker-compose-prebuilt.yml} of the suite's repository does: MongoDB, the suite
 * and its nginx in front. The suite has to know its public URL before it starts, so nginx
 * is bound to a fixed host port and the suite's URL is {@code https://localhost:<port>};
 * the system under test on the Docker host is reached from the containers as
 * {@code host.testcontainers.internal}. See {@link ConformanceSettings} for the images
 * and ports.
 *
 * <p>
 * A singleton, as the test classes share it and discover their modules from it while the
 * tests are collected. The containers are stopped with the JVM.
 */
public final class ConformanceSuite implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ConformanceSuite.class);

    private static final int SUITE_PORT = 8080;

    private static final int NGINX_PORT = 8443;

    private static final String NGINX_CERTIFICATE_PATH = "/etc/ssl/certs/nginx-selfsigned.crt";

    private static ConformanceSuite instance;

    private final URI baseUri;

    private final X509Certificate certificate;

    private final ConformanceApiClient client;

    private final Network network;

    private final List<GenericContainer<?>> containers;

    private volatile Path certificateFile;

    private ConformanceSuite(URI baseUri, X509Certificate certificate, Network network,
            List<GenericContainer<?>> containers) {
        this.baseUri = baseUri;
        this.certificate = certificate;
        this.network = network;
        this.containers = containers;
        this.client = new ConformanceApiClient(baseUri, sslContextTrusting(certificate));
    }

    public static synchronized ConformanceSuite instance() {
        if (instance == null) {
            instance = start();
            instance.client().waitUntilAvailable(Duration.ofMinutes(4));
            logger.info("Conformance suite {} is available at {}", ConformanceSettings.suiteImage(),
                    instance.baseUri());
        }
        return instance;
    }

    private static ConformanceSuite start() {
        int port = ConformanceSettings.suitePort();
        URI baseUri = URI.create("https://localhost:" + port);
        // the containers reach the system under test on the Docker host, which has to be
        // set
        // up before they start
        Testcontainers.exposeHostPorts(ConformanceSettings.receiverPort());
        logger.info("Starting conformance suite {} at {}", ConformanceSettings.suiteImage(), baseUri);

        Network network = Network.newNetwork();
        GenericContainer<?> mongodb = new GenericContainer<>(
                DockerImageName.parse(ConformanceSettings.suiteMongoDbImage()))
            .withNetwork(network)
            .withNetworkAliases("mongodb")
            .withExposedPorts(27017)
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("conformance.mongodb")))
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
        GenericContainer<?> server = new GenericContainer<>(DockerImageName.parse(ConformanceSettings.suiteImage()))
            .withNetwork(network)
            .withNetworkAliases("server")
            .withExposedPorts(SUITE_PORT)
            .withEnv("BASE_URL", baseUri.toString())
            .withEnv("BASE_MTLS_URL", baseUri.toString())
            .withEnv("MONGODB_HOST", "mongodb")
            // The suite pins MongoDB's feature compatibility version to the one it runs
            // in production (6.0) and refuses to start otherwise. The tests run a current
            // MongoDB, so the suite is told not to touch the version (an empty target
            // skips the step).
            .withEnv("OPENID_MONGODB_TARGETFEATURECOMPATIBILITYVERSION", "")
            // the 'dev' profile needs no login
            .withEnv("SPRING_PROFILES_ACTIVE", "dev")
            .withEnv("OIDC_GOOGLE_CLIENTID", "google-client")
            .withEnv("OIDC_GOOGLE_SECRET", "google-secret")
            .withEnv("OIDC_GITLAB_CLIENTID", "gitlab-client")
            .withEnv("OIDC_GITLAB_SECRET", "gitlab-secret")
            .dependsOn(mongodb)
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("conformance.suite")))
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(4)));
        GenericContainer<?> nginx = new GenericContainer<>(DockerImageName.parse(ConformanceSettings.suiteNginxImage()))
            .withNetwork(network)
            .withNetworkAliases("nginx")
            .withExposedPorts(NGINX_PORT)
            .withCreateContainerCmdModifier((command) -> {
                HostConfig hostConfig = (command.getHostConfig() != null) ? command.getHostConfig() : new HostConfig();
                command.withHostConfig(hostConfig
                    .withPortBindings(new PortBinding(Ports.Binding.bindPort(port), ExposedPort.tcp(NGINX_PORT))));
            })
            .dependsOn(server)
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("conformance.nginx")))
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

        List<GenericContainer<?>> containers = List.of(mongodb, server, nginx);
        try {
            containers.forEach(GenericContainer::start);
            return new ConformanceSuite(baseUri, nginxCertificate(nginx), network, containers);
        }
        catch (RuntimeException ex) {
            containers.reversed().forEach(GenericContainer::stop);
            network.close();
            throw ex;
        }
    }

    /**
     * The URL under which the tests reach the suite, also the base of the issuers of its
     * test instances ({@code <base>/test/a/<alias>}).
     */
    public URI baseUri() {
        return this.baseUri;
    }

    /**
     * The host under which the suite reaches the system under test.
     */
    public String systemUnderTestHost() {
        return "host.testcontainers.internal";
    }

    public ConformanceApiClient client() {
        return this.client;
    }

    /**
     * The TLS certificate of the suite.
     */
    public X509Certificate certificate() {
        return this.certificate;
    }

    /**
     * The TLS certificate of the suite as a PEM file, for the trust store of the system
     * under test.
     */
    public Path certificateFile() {
        Path file = this.certificateFile;
        if (file == null) {
            try {
                file = Files.createTempFile("conformance-suite-", ".pem");
                file.toFile().deleteOnExit();
                Files.writeString(file, "-----BEGIN CERTIFICATE-----\n"
                        + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(this.certificate.getEncoded())
                        + "\n-----END CERTIFICATE-----\n");
            }
            catch (IOException | CertificateEncodingException ex) {
                throw new IllegalStateException("Could not write the certificate of the conformance suite", ex);
            }
            this.certificateFile = file;
        }
        return file;
    }

    @Override
    public void close() {
        this.containers.reversed().forEach(GenericContainer::stop);
        this.network.close();
        synchronized (ConformanceSuite.class) {
            if (instance == this) {
                instance = null;
            }
        }
    }

    private static X509Certificate nginxCertificate(GenericContainer<?> nginx) {
        try {
            return nginx.copyFileFromContainer(NGINX_CERTIFICATE_PATH,
                    (input) -> (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        catch (Exception ex) {
            throw new IllegalStateException(
                    "Could not read the TLS certificate of the conformance suite from " + NGINX_CERTIFICATE_PATH, ex);
        }
    }

    private static SSLContext sslContextTrusting(X509Certificate certificate) {
        try {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("conformance-suite", certificate);
            TrustManagerFactory trustManagerFactory = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
            return sslContext;
        }
        catch (Exception ex) {
            throw new IllegalStateException("Could not create an SSL context trusting the conformance suite", ex);
        }
    }

}
