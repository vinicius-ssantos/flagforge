package io.github.viniciusssantos.flagforge.distribution;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the delivery relay.
 *
 * @param pollInterval how often the recovery poll runs. Delivery latency normally comes from the
 *                     post-commit trigger, so this only bounds how long a missed trigger can delay
 *                     an event.
 * @param batchSize    how many events one pass claims. Bounds the work held under one transaction.
 * @param maxAttempts  attempts before an event is abandoned as FAILED, so one poisoned event cannot
 *                     be retried forever.
 * @param retryBackoff first retry delay, doubled per attempt.
 * @param maxBackoff   ceiling for the doubling, so a long-failing consumer is still retried
 *                     regularly rather than drifting into hours.
 */
@ConfigurationProperties(prefix = "flagforge.distribution.relay")
public record OutboxRelayProperties(
        Duration pollInterval,
        int batchSize,
        int maxAttempts,
        Duration retryBackoff,
        Duration maxBackoff) {

    public OutboxRelayProperties {
        pollInterval = pollInterval == null ? Duration.ofSeconds(5) : pollInterval;
        batchSize = batchSize <= 0 ? 100 : batchSize;
        maxAttempts = maxAttempts <= 0 ? 8 : maxAttempts;
        retryBackoff = retryBackoff == null ? Duration.ofSeconds(2) : retryBackoff;
        maxBackoff = maxBackoff == null ? Duration.ofMinutes(5) : maxBackoff;
    }

    /**
     * Delay before the next attempt, doubling per attempt and capped.
     *
     * <p>The shift is bounded before it is applied: without the cap on the exponent, a high attempt
     * count would overflow the shift and produce a nonsensical delay.
     */
    public Duration backoffFor(int attempts) {
        int exponent = Math.min(Math.max(attempts - 1, 0), 16);
        Duration delay = retryBackoff.multipliedBy(1L << exponent);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
