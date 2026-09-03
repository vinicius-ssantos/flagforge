package io.github.viniciusssantos.flagforge.evaluation;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

/**
 * Cache telemetry.
 *
 * <p>Tagged by layer and outcome only. The global meter filter rejects tenant identifiers, and a
 * hit ratio per environment would be unbounded, so neither is available even if it were allowed.
 */
@Component
final class EvaluationCacheMetrics {

    private static final String LOOKUPS_METRIC = "flagforge.evaluation.cache.lookups";

    private final MeterRegistry meterRegistry;

    EvaluationCacheMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    void snapshotHit() {
        count("snapshot", "hit");
    }

    void snapshotMiss() {
        count("snapshot", "miss");
    }

    void pointerHit() {
        count("pointer", "hit");
    }

    void pointerMiss() {
        count("pointer", "miss");
    }

    /**
     * A last-known-good answer served while the source of truth was unreachable.
     */
    void servedStale() {
        count("snapshot", "stale");
    }

    private void count(String layer, String outcome) {
        meterRegistry.counter(LOOKUPS_METRIC, "layer", layer, "outcome", outcome).increment();
    }
}
