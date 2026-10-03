package org.easyssf.receiver.spring.boot.autoconfigure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.Configurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The auto-configurations of the receiver refer to auto-configurations of Spring Boot to
 * be ordered relative to them. That must not move those: some of them only work in the
 * order they have without the receiver.
 */
class SsfReceiverAutoConfigurationOrderTests {

    private static final String OURS = "org.easyssf.";

    @Test
    void doesNotChangeTheOrderOfOtherAutoConfigurations() {
        List<Class<?>> ours = new ArrayList<>();
        List<Class<?>> others = new ArrayList<>();
        for (String name : ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())) {
            (name.startsWith(OURS) ? ours : others).add(ClassUtils.resolveClassName(name, getClass().getClassLoader()));
        }
        assertThat(ours).hasSizeGreaterThanOrEqualTo(8);
        assertThat(others).hasSizeGreaterThan(50);

        List<String> orderWithoutOurs = sorted(others);
        List<Class<?>> all = new ArrayList<>(others);
        all.addAll(ours);
        List<String> orderWithOurs = sorted(all).stream().filter((name) -> !name.startsWith(OURS)).toList();

        assertThat(orderWithOurs).containsExactlyElementsOf(orderWithoutOurs);
    }

    @Test
    void ordersOwnAutoConfigurationsAsTheyDependOnEachOther() {
        List<Class<?>> ours = new ArrayList<>();
        for (String name : ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())) {
            ours.add(ClassUtils.resolveClassName(name, getClass().getClassLoader()));
        }
        List<String> order = sorted(ours).stream()
            .filter((name) -> name.startsWith(OURS))
            .map((name) -> name.substring(name.lastIndexOf('.') + 1))
            .toList();
        // stores, metrics and the RestClient have to be registered before the defaults
        // they replace
        assertThat(order.indexOf("SsfReceiverAutoConfiguration"))
            .isGreaterThan(order.indexOf("SsfReceiverJdbcAutoConfiguration"))
            .isGreaterThan(order.indexOf("SsfReceiverMetricsAutoConfiguration"))
            .isGreaterThan(order.indexOf("SsfReceiverRestClientAutoConfiguration"));
        assertThat(order.indexOf("SsfReceiverResourceServerAutoConfiguration"))
            .isGreaterThan(order.indexOf("SsfReceiverJdbcAutoConfiguration"))
            .isGreaterThan(order.indexOf("SsfReceiverAutoConfiguration"));
        assertThat(order.indexOf("SsfReceiverPushSecurityAutoConfiguration"))
            .isGreaterThan(order.indexOf("SsfReceiverPushAutoConfiguration"));
        assertThat(order.indexOf("SsfReceiverPushAutoConfiguration"))
            .isGreaterThan(order.indexOf("SsfReceiverAutoConfiguration"));
    }

    private static List<String> sorted(List<Class<?>> autoConfigurations) {
        Class<?>[] sorted = Configurations
            .getClasses(AutoConfigurations.of(autoConfigurations.toArray(Class<?>[]::new)));
        return Arrays.stream(sorted).map(Class::getName).toList();
    }

}
