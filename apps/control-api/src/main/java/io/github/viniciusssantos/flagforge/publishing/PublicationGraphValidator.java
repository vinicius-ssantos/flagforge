package io.github.viniciusssantos.flagforge.publishing;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedFlag;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.Condition;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.FlagTarget;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.Prerequisite;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.Segment;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.TargetingConfiguration;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.TargetingRule;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.TargetingValidationException;

import org.springframework.stereotype.Component;

@Component
final class PublicationGraphValidator {

    private static final Comparator<Condition> CONDITION_ORDER = Comparator
            .comparing((Condition condition) -> condition.getClass().getName())
            .thenComparing(Object::toString);

    TargetingConfiguration validate(
            List<PublishedFlag> publishedFlags,
            TargetingConfiguration requestedConfiguration) {
        Objects.requireNonNull(publishedFlags, "publishedFlags is required");
        TargetingConfiguration candidate = requestedConfiguration == null
                ? defaultConfiguration(publishedFlags)
                : canonicalize(requestedConfiguration);

        try {
            TargetingEngine.validate(candidate);
        } catch (TargetingValidationException exception) {
            throw new PublicationGraphException(
                    exception.errorCode().name(),
                    exception.getMessage(),
                    exception);
        }

        Map<String, PublishedFlag> publishedByKey = publishedFlags.stream()
                .collect(Collectors.toMap(
                        PublishedFlag::key,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
        if (candidate.flags().size() != publishedByKey.size()) {
            throw graphFailure(
                    GraphValidationError.FLAG_SCOPE_MISMATCH,
                    "Targeting graph must contain every active flag exactly once");
        }

        for (FlagTarget target : candidate.flags()) {
            PublishedFlag published = publishedByKey.get(target.key());
            if (published == null) {
                throw graphFailure(
                        GraphValidationError.FLAG_SCOPE_MISMATCH,
                        "Targeting graph references a flag outside the publication candidate");
            }
            Set<String> publishedVariants = published.variants().stream()
                    .map(PublishedSnapshotCodec.PublishedVariant::key)
                    .collect(Collectors.toUnmodifiableSet());
            if (!publishedVariants.equals(target.variants())) {
                throw graphFailure(
                        GraphValidationError.VARIANT_SCOPE_MISMATCH,
                        "Targeting graph variants must match the published flag variants");
            }
            if (!published.defaultVariant().equals(target.defaultVariant())) {
                throw graphFailure(
                        GraphValidationError.DEFAULT_VARIANT_MISMATCH,
                        "Targeting graph default variant must match the published flag");
            }
        }
        return candidate;
    }

    private static TargetingConfiguration defaultConfiguration(
            List<PublishedFlag> publishedFlags) {
        List<FlagTarget> flags = publishedFlags.stream()
                .map(flag -> new FlagTarget(
                        flag.key(),
                        flag.variants().stream()
                                .map(PublishedSnapshotCodec.PublishedVariant::key)
                                .collect(Collectors.toUnmodifiableSet()),
                        flag.defaultVariant(),
                        List.of(),
                        List.of()))
                .toList();
        return new TargetingConfiguration(flags, List.of());
    }

    private static TargetingConfiguration canonicalize(
            TargetingConfiguration configuration) {
        Objects.requireNonNull(configuration, "targetingConfiguration is required");
        List<FlagTarget> flags = configuration.flags().stream()
                .sorted(Comparator.comparing(FlagTarget::key))
                .map(PublicationGraphValidator::canonicalizeFlag)
                .toList();
        List<Segment> segments = configuration.segments().stream()
                .sorted(Comparator.comparing(Segment::key))
                .map(segment -> new Segment(
                        segment.key(),
                        segment.includedTargetingKeys(),
                        segment.excludedTargetingKeys(),
                        sortedConditions(segment.conditions())))
                .toList();
        return new TargetingConfiguration(flags, segments);
    }

    private static FlagTarget canonicalizeFlag(FlagTarget flag) {
        List<Prerequisite> prerequisites = flag.prerequisites().stream()
                .sorted(Comparator
                        .comparing(Prerequisite::flagKey)
                        .thenComparing(Prerequisite::expectedVariant))
                .toList();
        List<TargetingRule> rules = flag.rules().stream()
                .sorted(Comparator
                        .comparingInt(TargetingRule::priority)
                        .thenComparing(TargetingRule::key))
                .map(rule -> new TargetingRule(
                        rule.key(),
                        rule.priority(),
                        sortedConditions(rule.conditions()),
                        rule.variantKey()))
                .toList();
        return new FlagTarget(
                flag.key(),
                flag.variants(),
                flag.defaultVariant(),
                prerequisites,
                rules);
    }

    private static List<Condition> sortedConditions(List<Condition> conditions) {
        return conditions.stream().sorted(CONDITION_ORDER).toList();
    }

    private static PublicationGraphException graphFailure(
            GraphValidationError error,
            String message) {
        return new PublicationGraphException(error.name(), message, null);
    }

    enum GraphValidationError {
        FLAG_SCOPE_MISMATCH,
        VARIANT_SCOPE_MISMATCH,
        DEFAULT_VARIANT_MISMATCH
    }

    static final class PublicationGraphException extends IllegalArgumentException {

        private final String validationCode;

        private PublicationGraphException(
                String validationCode,
                String message,
                Throwable cause) {
            super(message, cause);
            this.validationCode = Objects.requireNonNull(
                    validationCode,
                    "validationCode is required");
        }

        String validationCode() {
            return validationCode;
        }
    }
}
