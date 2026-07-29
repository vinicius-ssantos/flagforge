package io.github.viniciusssantos.flagforge.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.SdkPrincipal;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationApi.ValueType;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.TargetingConfiguration;

public interface EvaluationSnapshotProvider {

    Optional<EvaluationSnapshot> load(
            SdkPrincipal principal,
            String flagKey);

    record VariantValue(
            ValueType type,
            Object value) {

        public VariantValue {
            Objects.requireNonNull(type, "variant type is required");
            Objects.requireNonNull(value, "variant value is required");
        }
    }

    record SplitAllocation(
            String variantKey,
            int units) {

        public SplitAllocation {
            Objects.requireNonNull(variantKey, "variant key is required");
            if (units <= 0) {
                throw new IllegalArgumentException(
                        "split allocation units must be positive");
            }
        }
    }

    record PercentageSplit(
            String allocationKey,
            List<SplitAllocation> allocations) {

        public PercentageSplit {
            Objects.requireNonNull(allocationKey, "allocation key is required");
            allocations = List.copyOf(Objects.requireNonNull(
                    allocations,
                    "split allocations are required"));
            if (allocations.isEmpty()) {
                throw new IllegalArgumentException(
                        "split allocations cannot be empty");
            }
        }
    }

    record EvaluationSnapshot(
            UUID organizationId,
            UUID projectId,
            UUID environmentId,
            String configurationVersion,
            boolean enabled,
            boolean stale,
            ValueType valueType,
            String defaultVariant,
            Map<String, VariantValue> variants,
            TargetingConfiguration targetingConfiguration,
            PercentageSplit percentageSplit) {

        public EvaluationSnapshot {
            Objects.requireNonNull(organizationId, "organization id is required");
            Objects.requireNonNull(projectId, "project id is required");
            Objects.requireNonNull(environmentId, "environment id is required");
            Objects.requireNonNull(
                    configurationVersion,
                    "configuration version is required");
            Objects.requireNonNull(valueType, "value type is required");
            Objects.requireNonNull(defaultVariant, "default variant is required");
            variants = Map.copyOf(Objects.requireNonNull(
                    variants,
                    "variants are required"));
            Objects.requireNonNull(
                    targetingConfiguration,
                    "targeting configuration is required");
        }
    }
}
