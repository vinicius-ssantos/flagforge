package io.github.viniciusssantos.flagforge.evaluation;

import io.github.viniciusssantos.flagforge.evaluation.EvaluationApi.Response;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

@Component
final class EvaluationMetrics {

    private static final String METRIC_NAME =
            "flagforge.evaluation.requests";

    private final MeterRegistry meterRegistry;

    EvaluationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    void record(Response response) {
        meterRegistry.counter(
                        METRIC_NAME,
                        "reason",
                        response.reason().name(),
                        "value_type",
                        response.valueType().name(),
                        "error_code",
                        response.error().code().name(),
                        "stale",
                        Boolean.toString(response.stale()))
                .increment();
    }
}
