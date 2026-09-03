package io.github.viniciusssantos.flagforge.distribution;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRelayPropertiesTests {

    private final OutboxRelayProperties properties = new OutboxRelayProperties(
            true,
            Duration.ofSeconds(5),
            100,
            8,
            Duration.ofSeconds(2),
            Duration.ofMinutes(5));

    @Test
    void doublesTheDelayWithEachAttempt() {
        assertThat(properties.backoffFor(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.backoffFor(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(properties.backoffFor(3)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void stopsGrowingAtTheCeiling() {
        assertThat(properties.backoffFor(20)).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.backoffFor(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void treatsTheFirstAttemptAsTheBaseDelay() {
        assertThat(properties.backoffFor(0)).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.backoffFor(-1)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void fallsBackToSafeDefaultsWhenUnconfigured() {
        OutboxRelayProperties defaults = new OutboxRelayProperties(true, null, 0, 0, null, null);

        assertThat(defaults.pollInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.batchSize()).isEqualTo(100);
        assertThat(defaults.maxAttempts()).isEqualTo(8);
        assertThat(defaults.backoffFor(1)).isEqualTo(Duration.ofSeconds(2));
    }
}
