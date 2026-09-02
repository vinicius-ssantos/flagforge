package io.github.viniciusssantos.flagforge.distribution;

import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

/**
 * Relay telemetry.
 *
 * <p>Tags are bounded on purpose. The global meter filter rejects tenant and subject identifiers,
 * and a backlog broken down per environment would be unbounded anyway, so outcome is the only
 * dimension here.
 */
@Component
final class OutboxRelayMetrics {

    private static final String EVENTS_METRIC = "flagforge.distribution.relay.events";
    private static final String BACKLOG_METRIC = "flagforge.distribution.outbox.pending";

    private final MeterRegistry meterRegistry;
    private final AtomicLong pendingBacklog = new AtomicLong();

    OutboxRelayMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge(BACKLOG_METRIC, pendingBacklog);
    }

    void delivered() {
        count("delivered");
    }

    void retried() {
        count("retried");
    }

    void abandoned() {
        count("abandoned");
    }

    /**
     * Publishes the backlog measured by the last relay pass.
     *
     * <p>The gauge reads a cached number rather than querying on scrape, so metric collection never
     * puts load on the database.
     */
    void recordBacklog(long pending) {
        pendingBacklog.set(pending);
    }

    private void count(String outcome) {
        meterRegistry.counter(EVENTS_METRIC, "outcome", outcome).increment();
    }
}
