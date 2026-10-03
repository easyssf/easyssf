package org.easyssf.conformance.receiver;

import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.http.SsfHttpClient;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * The HTTP client the receiver calls the conformance suite with. It trusts the
 * certificate of the suite as configured, see {@link CtsProperties.Transmitter}.
 */
@Configuration(proxyBeanMethods = false)
class TransmitterHttpClientConfiguration {

    @Bean
    SsfHttpClient transmitterHttpClient(CtsProperties properties, SslBundles sslBundles) throws Exception {
        CtsProperties.Transmitter transmitter = properties.getTransmitter();
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (transmitter.isTrustAllCertificates()) {
            builder.sslContext(trustAll());
        }
        else if (StringUtils.hasText(transmitter.getSslBundle())) {
            builder.sslContext(sslBundles.getBundle(transmitter.getSslBundle()).createSslContext());
        }
        JdkSsfHttpClient httpClient = new JdkSsfHttpClient(builder.build(), Duration.ofSeconds(30));
        httpClient.setUserAgent("easyssf-receiver-conformance");
        return httpClient;
    }

    private static SSLContext trustAll() throws Exception {
        X509TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] { trustAll }, new SecureRandom());
        return sslContext;
    }

}
