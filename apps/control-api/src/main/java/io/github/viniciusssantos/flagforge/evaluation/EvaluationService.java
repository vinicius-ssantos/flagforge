package io.github.viniciusssantos.flagforge.evaluation;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import io.github.viniciusssantos.flagforge.allocation.DeterministicRolloutAllocator;
import io.github.viniciusssantos.flagforge.allocation.DeterministicRolloutAllocator.AlgorithmVersion;
import io.github.viniciusssantos.flagforge.allocation.DeterministicRolloutAllocator.Allocation;
import io.github.viniciusssantos.flagforge.allocation.DeterministicRolloutAllocator.RolloutInput;
import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.SdkPrincipal;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationApi.ErrorCode;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationApi.ErrorMetadata;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationApi.EvaluationRequestException;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationApi.Reason;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationApi.Request;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationApi.Response;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationApi.TypedAttribute;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationApi.ValueType;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationSnapshotProvider.EvaluationSnapshot;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationSnapshotProvider.PercentageSplit;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationSnapshotProvider.SplitAllocation;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationSnapshotProvider.VariantValue;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.AttributeValue;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.BooleanValue;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.EvaluationContext;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.EvaluationReason;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.EvaluationResult;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.NumberValue;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.StringValue;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.TargetingValidationException;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

@Service
public class EvaluationService {

    private static final Pattern FLAG_KEY_PATTERN =
            Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private final EvaluationSnapshotProvider snapshotProvider;
    private final EvaluationMetrics metrics;

    public EvaluationService(
            EvaluationSnapshotProvider snapshotProvider,
            EvaluationMetrics metrics) {
        this.snapshotProvider = snapshotProvider;
        this.metrics = metrics;
    }

    public Response evaluate(
            SdkPrincipal principal,
            String flagKey,
            Request request) {
        Objects.requireNonNull(principal, "SDK principal is required");
        Objects.requireNonNull(request, "evaluation request is required");
        String normalizedFlagKey = normalizeFlagKey(flagKey);
        Object fallbackValue = parseRequestedValue(
                request.type(),
                request.defaultValue(),
                "defaultValue");
        EvaluationContext context = createContext(request);

        Optional<EvaluationSnapshot> loaded;
        try {
            loaded = snapshotProvider.load(principal, normalizedFlagKey);
        } catch (DataAccessException exception) {
            return record(errorResponse(
                    normalizedFlagKey,
                    request.type(),
                    fallbackValue,
                    null,
                    ErrorCode.SNAPSHOT_UNAVAILABLE,
                    "Evaluation snapshot is unavailable; caller fallback returned."));
        }
        if (loaded.isEmpty()) {
            return record(errorResponse(
                    normalizedFlagKey,
                    request.type(),
                    fallbackValue,
                    null,
                    ErrorCode.FLAG_NOT_FOUND,
                    "Flag not found; caller fallback returned."));
        }

        EvaluationSnapshot snapshot = loaded.get();
        if (!snapshot.enabled()) {
            return record(successResponse(
                    normalizedFlagKey,
                    request.type(),
                    fallbackValue,
                    null,
                    Reason.DISABLED,
                    null,
                    snapshot.configurationVersion(),
                    null,
                    null,
                    null,
                    false));
        }
        if (snapshot.valueType() != request.type()) {
            return record(errorResponse(
                    normalizedFlagKey,
                    request.type(),
                    fallbackValue,
                    snapshot.configurationVersion(),
                    ErrorCode.TYPE_MISMATCH,
                    "Requested type does not match flag type; caller fallback returned."));
        }

        EvaluationResult targetingResult;
        try {
            targetingResult = TargetingEngine.evaluate(
                    snapshot.targetingConfiguration(),
                    normalizedFlagKey,
                    context);
        } catch (TargetingValidationException exception) {
            return record(errorResponse(
                    normalizedFlagKey,
                    request.type(),
                    fallbackValue,
                    snapshot.configurationVersion(),
                    ErrorCode.INVALID_CONFIGURATION,
                    "Evaluation configuration is invalid; caller fallback returned."));
        }
        if (targetingResult.reason() == EvaluationReason.ERROR) {
            return record(errorResponse(
                    normalizedFlagKey,
                    request.type(),
                    fallbackValue,
                    snapshot.configurationVersion(),
                    ErrorCode.INVALID_CONTEXT,
                    "Evaluation context is invalid; caller fallback returned."));
        }

        Decision decision = decide(
                snapshot,
                normalizedFlagKey,
                request.targetingKey(),
                targetingResult);
        if (decision.errorCode() != ErrorCode.NONE) {
            return record(errorResponse(
                    normalizedFlagKey,
                    request.type(),
                    fallbackValue,
                    snapshot.configurationVersion(),
                    decision.errorCode(),
                    decision.errorMessage()));
        }

        VariantValue variantValue = snapshot.variants().get(decision.variantKey());
        if (variantValue == null || variantValue.type() != request.type()) {
            return record(errorResponse(
                    normalizedFlagKey,
                    request.type(),
                    fallbackValue,
                    snapshot.configurationVersion(),
                    ErrorCode.INVALID_CONFIGURATION,
                    "Evaluation configuration is invalid; caller fallback returned."));
        }

        Reason effectiveReason = snapshot.stale()
                ? Reason.STALE
                : decision.reason();
        String sourceReason = snapshot.stale()
                ? decision.reason().name()
                : null;
        return record(successResponse(
                normalizedFlagKey,
                request.type(),
                variantValue.value(),
                decision.variantKey(),
                effectiveReason,
                sourceReason,
                snapshot.configurationVersion(),
                targetingResult.matchedRuleKey(),
                targetingResult.failedPrerequisiteKey(),
                decision.bucket(),
                snapshot.stale()));
    }

    private Decision decide(
            EvaluationSnapshot snapshot,
            String flagKey,
            String targetingKey,
            EvaluationResult targetingResult) {
        if (targetingResult.reason() == EvaluationReason.DEFAULT
                && snapshot.percentageSplit() != null) {
            return splitDecision(
                    snapshot,
                    flagKey,
                    targetingKey,
                    snapshot.percentageSplit());
        }
        Reason reason = switch (targetingResult.reason()) {
            case TARGETING_MATCH -> Reason.TARGETING_MATCH;
            case DEFAULT -> Reason.DEFAULT;
            case PREREQUISITE_FAILED -> Reason.PREREQUISITE_FAILED;
            case ERROR -> throw new IllegalStateException(
                    "error result must be handled before deciding");
        };
        return Decision.success(
                targetingResult.variantKey(),
                reason,
                null);
    }

    private Decision splitDecision(
            EvaluationSnapshot snapshot,
            String flagKey,
            String targetingKey,
            PercentageSplit split) {
        long total = split.allocations().stream()
                .mapToLong(SplitAllocation::units)
                .sum();
        if (total != DeterministicRolloutAllocator.BUCKET_COUNT) {
            return Decision.error(
                    ErrorCode.INVALID_CONFIGURATION,
                    "Evaluation configuration is invalid; caller fallback returned.");
        }

        Allocation allocation = DeterministicRolloutAllocator.allocate(
                new RolloutInput(
                        AlgorithmVersion.V1,
                        snapshot.organizationId(),
                        snapshot.projectId(),
                        snapshot.environmentId(),
                        flagKey,
                        split.allocationKey(),
                        targetingKey));
        int cumulative = 0;
        for (SplitAllocation candidate : split.allocations()) {
            cumulative += candidate.units();
            if (allocation.bucket() < cumulative) {
                return Decision.success(
                        candidate.variantKey(),
                        Reason.SPLIT,
                        allocation.bucket());
            }
        }
        return Decision.error(
                ErrorCode.INVALID_CONFIGURATION,
                "Evaluation configuration is invalid; caller fallback returned.");
    }

    private static EvaluationContext createContext(Request request) {
        Map<String, AttributeValue> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, TypedAttribute> entry
                : request.attributes().entrySet()) {
            attributes.put(
                    entry.getKey(),
                    parseAttribute(entry.getValue()));
        }
        try {
            return new EvaluationContext(
                    request.targetingKey(),
                    attributes);
        } catch (TargetingValidationException exception) {
            throw new EvaluationRequestException(
                    ErrorCode.INVALID_CONTEXT,
                    "Evaluation context is invalid");
        }
    }

    private static AttributeValue parseAttribute(TypedAttribute attribute) {
        JsonNode value = attribute.value();
        return switch (attribute.type()) {
            case BOOLEAN -> {
                if (!value.isBoolean()) {
                    throw invalidContextAttribute();
                }
                yield new BooleanValue(value.booleanValue());
            }
            case STRING -> {
                if (!value.isTextual()) {
                    throw invalidContextAttribute();
                }
                yield new StringValue(value.stringValue());
            }
            case NUMBER -> {
                if (!value.isNumber()) {
                    throw invalidContextAttribute();
                }
                BigDecimal decimal = value.decimalValue();
                yield new NumberValue(decimal);
            }
        };
    }

    private static Object parseRequestedValue(
            ValueType valueType,
            JsonNode value,
            String fieldName) {
        return switch (valueType) {
            case BOOLEAN -> {
                if (!value.isBoolean()) {
                    throw invalidRequestedValue(fieldName, valueType);
                }
                yield value.booleanValue();
            }
            case STRING -> {
                if (!value.isTextual()) {
                    throw invalidRequestedValue(fieldName, valueType);
                }
                yield value.stringValue();
            }
        };
    }

    private static EvaluationRequestException invalidRequestedValue(
            String fieldName,
            ValueType valueType) {
        return new EvaluationRequestException(
                ErrorCode.INVALID_REQUEST,
                fieldName + " must contain an exact " + valueType + " value");
    }

    private static EvaluationRequestException invalidContextAttribute() {
        return new EvaluationRequestException(
                ErrorCode.INVALID_CONTEXT,
                "Attribute value does not match its declared type");
    }

    private static String normalizeFlagKey(String flagKey) {
        Objects.requireNonNull(flagKey, "flag key is required");
        String normalized = flagKey.strip().toLowerCase(Locale.ROOT);
        if (!FLAG_KEY_PATTERN.matcher(normalized).matches()) {
            throw new EvaluationRequestException(
                    ErrorCode.INVALID_REQUEST,
                    "Flag key has an invalid format");
        }
        return normalized;
    }

    private Response record(Response response) {
        metrics.record(response);
        return response;
    }

    private static Response successResponse(
            String flagKey,
            ValueType valueType,
            Object value,
            String variant,
            Reason reason,
            String sourceReason,
            String configurationVersion,
            String matchedRuleKey,
            String failedPrerequisiteKey,
            Integer bucket,
            boolean stale) {
        return new Response(
                flagKey,
                valueType,
                value,
                variant,
                reason,
                sourceReason,
                configurationVersion,
                ErrorMetadata.none(),
                matchedRuleKey,
                failedPrerequisiteKey,
                bucket,
                stale);
    }

    private static Response errorResponse(
            String flagKey,
            ValueType valueType,
            Object fallbackValue,
            String configurationVersion,
            ErrorCode errorCode,
            String errorMessage) {
        return new Response(
                flagKey,
                valueType,
                fallbackValue,
                null,
                Reason.ERROR,
                null,
                configurationVersion,
                new ErrorMetadata(errorCode, errorMessage),
                null,
                null,
                null,
                false);
    }

    private record Decision(
            String variantKey,
            Reason reason,
            Integer bucket,
            ErrorCode errorCode,
            String errorMessage) {

        private static Decision success(
                String variantKey,
                Reason reason,
                Integer bucket) {
            return new Decision(
                    variantKey,
                    reason,
                    bucket,
                    ErrorCode.NONE,
                    null);
        }

        private static Decision error(
                ErrorCode errorCode,
                String errorMessage) {
            return new Decision(
                    null,
                    Reason.ERROR,
                    null,
                    errorCode,
                    errorMessage);
        }
    }
}
