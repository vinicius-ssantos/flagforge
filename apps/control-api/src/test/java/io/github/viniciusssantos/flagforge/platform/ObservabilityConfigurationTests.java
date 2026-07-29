package io.github.viniciusssantos.flagforge.platform;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityConfigurationTests {

    @Test
    void rejectsSensitiveAndHighCardinalityTagKeys() {
        ObservabilityConfiguration configuration = new ObservabilityConfiguration();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(configuration.rejectSensitiveOrHighCardinalityTags());

        Counter.builder("flagforge.evaluation.test")
                .tag("targeting.key", "customer-123")
                .register(registry)
                .increment();

        assertThat(registry.find("flagforge.evaluation.test").counter()).isNull();
    }

    @Test
    void allowsBoundedOperationalTags() {
        ObservabilityConfiguration configuration = new ObservabilityConfiguration();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(configuration.rejectSensitiveOrHighCardinalityTags());

        Counter.builder("flagforge.evaluation.test")
                .tag("reason", "DEFAULT")
                .register(registry)
                .increment();

        assertThat(registry.find("flagforge.evaluation.test").counter())
                .isNotNull()
                .extracting(Counter::count)
                .isEqualTo(1.0);
    }
}
