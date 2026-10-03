package org.easyssf.conformance.receiver;

import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.test.conformance.receiver.ConformanceRunner;
import org.easyssf.test.conformance.receiver.ConformanceSuiteModules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The runner that plays the scenarios, and the view on the suite's running modules, as
 * beans.
 */
@Configuration(proxyBeanMethods = false)
class ConformanceConfiguration {

    @Bean
    ConformanceRunner conformanceRunner(CtsProperties properties, SsfHttpClient httpClient) {
        return new ConformanceRunner(properties, httpClient);
    }

    @Bean
    ConformanceSuiteModules conformanceSuiteModules(CtsProperties properties, SsfHttpClient httpClient) {
        return new ConformanceSuiteModules(properties, httpClient);
    }

}
