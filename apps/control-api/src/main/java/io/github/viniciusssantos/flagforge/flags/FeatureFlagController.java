package io.github.viniciusssantos.flagforge.flags;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.BooleanVariant;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.CreateFlagCommand;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.FeatureFlag;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.FlagValidationException;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.LifecycleType;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.StringVariant;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.ValidationCode;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.ValueType;
import io.github.viniciusssantos.flagforge.flags.FeatureFlagService.Variant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/flags")
final class FeatureFlagController {

    private final FeatureFlagService featureFlagService;

    FeatureFlagController(FeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
    }

    @PostMapping
    ResponseEntity<FlagResponse> create(
            @PathVariable UUID projectId,
            @RequestBody CreateFlagRequest request) {
        if (request == null) {
            throw new FlagValidationException(
                    ValidationCode.INVALID_TEXT,
                    "Request body is required");
        }
        requireField(request.key(), "key");
        requireField(request.displayName(), "displayName");
        requireField(request.ownerId(), "ownerId");
        requireField(request.defaultVariantKey(), "defaultVariantKey");
        requireField(request.valueType(), "valueType");
        requireField(request.lifecycleType(), "lifecycleType");
        FeatureFlag flag = featureFlagService.create(new CreateFlagCommand(
                projectId,
                request.key(),
                request.displayName(),
                request.description(),
                request.ownerId(),
                request.valueType(),
                request.lifecycleType(),
                request.expectedRemovalDate(),
                request.defaultVariantKey(),
                variants(request)));
        return ResponseEntity.status(HttpStatus.CREATED).body(FlagResponse.of(flag));
    }

    @GetMapping("/{flagId}")
    FlagResponse find(@PathVariable UUID projectId, @PathVariable UUID flagId) {
        return FlagResponse.of(featureFlagService.find(projectId, flagId));
    }

    @DeleteMapping("/{flagId}")
    FlagResponse archive(@PathVariable UUID projectId, @PathVariable UUID flagId) {
        return FlagResponse.of(featureFlagService.archive(projectId, flagId));
    }

    /**
     * Rejects a missing required field at the boundary with a stable code.
     *
     * <p>The domain signals absent input with {@code NullPointerException}, which was invisible
     * while these use cases were only called from tests. Over HTTP it would surface as a 500 and
     * leak an internal failure, so required fields are checked before the command is built.
     */
    private static void requireField(Object value, String fieldName) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new FlagValidationException(
                    ValidationCode.INVALID_TEXT,
                    fieldName + " is required");
        }
    }

    /**
     * Builds typed variants without inferring the type from the JSON shape.
     *
     * <p>The declared flag type decides which field of each variant is read, so a boolean flag
     * cannot be given a string value by writing it differently in the request. Unsupported types
     * reach the service, which owns the {@code UNSUPPORTED_VALUE_TYPE} contract.
     */
    private static List<Variant> variants(CreateFlagRequest request) {
        List<VariantRequest> declared = request.variants() == null ? List.of() : request.variants();
        List<Variant> variants = new ArrayList<>(declared.size());
        for (VariantRequest variant : declared) {
            if (variant == null || variant.key() == null) {
                throw new FlagValidationException(
                        ValidationCode.INVALID_VARIANT,
                        "Variant key is required");
            }
            variants.add(toVariant(request.valueType(), variant));
        }
        return variants;
    }

    private static Variant toVariant(ValueType valueType, VariantRequest variant) {
        if (valueType == ValueType.BOOLEAN) {
            if (variant.booleanValue() == null) {
                throw new FlagValidationException(
                        ValidationCode.TYPE_MISMATCH,
                        "Boolean variant requires booleanValue");
            }
            return new BooleanVariant(variant.key(), variant.booleanValue());
        }
        if (valueType == ValueType.STRING) {
            if (variant.stringValue() == null) {
                throw new FlagValidationException(
                        ValidationCode.TYPE_MISMATCH,
                        "String variant requires stringValue");
            }
            return new StringVariant(variant.key(), variant.stringValue());
        }
        throw new FlagValidationException(
                ValidationCode.UNSUPPORTED_VALUE_TYPE,
                "Only BOOLEAN and STRING flags can be created");
    }

    record CreateFlagRequest(
            String key,
            String displayName,
            String description,
            String ownerId,
            ValueType valueType,
            LifecycleType lifecycleType,
            LocalDate expectedRemovalDate,
            String defaultVariantKey,
            List<VariantRequest> variants) {
    }

    record VariantRequest(String key, Boolean booleanValue, String stringValue) {
    }

    record VariantResponse(String key, ValueType valueType, Object value) {
    }

    record FlagResponse(
            UUID id,
            UUID projectId,
            String key,
            String displayName,
            ValueType valueType,
            LifecycleType lifecycleType,
            String defaultVariantKey,
            String state,
            List<VariantResponse> variants) {

        static FlagResponse of(FeatureFlag flag) {
            List<VariantResponse> variants = flag.variants().stream()
                    .map(FlagResponse::toResponse)
                    .toList();
            return new FlagResponse(
                    flag.id(),
                    flag.projectId(),
                    flag.key(),
                    flag.displayName(),
                    flag.valueType(),
                    flag.lifecycleType(),
                    flag.defaultVariantKey(),
                    flag.state().name(),
                    variants);
        }

        private static VariantResponse toResponse(Variant variant) {
            return switch (variant) {
                case BooleanVariant booleanVariant -> new VariantResponse(
                        booleanVariant.key(),
                        ValueType.BOOLEAN,
                        booleanVariant.value());
                case StringVariant stringVariant -> new VariantResponse(
                        stringVariant.key(),
                        ValueType.STRING,
                        stringVariant.value());
            };
        }
    }
}
