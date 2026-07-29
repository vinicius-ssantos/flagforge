package io.github.viniciusssantos.flagforge.targeting;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class TargetingEngine {

    public static final int MAX_FLAGS = 256;
    public static final int MAX_SEGMENTS = 256;
    public static final int MAX_RULES_PER_FLAG = 128;
    public static final int MAX_CONDITIONS = 32;
    public static final int MAX_ATTRIBUTES = 256;
    public static final int MAX_GRAPH_DEPTH = 64;

    private static final Pattern KEY_PATTERN = Pattern.compile(
            "[a-z0-9](?:[a-z0-9._-]{0,126}[a-z0-9])?");
    private static final Pattern SEMVER_PATTERN = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    private TargetingEngine() {
    }

    public static EvaluationResult evaluate(
            TargetingConfiguration configuration,
            String flagKey,
            EvaluationContext context) {
        Objects.requireNonNull(configuration, "configuration is required");
        Objects.requireNonNull(context, "context is required");
        validate(configuration);

        String normalizedFlagKey = normalizeKey(flagKey, "flagKey");
        Map<String, FlagTarget> flags = indexFlags(configuration.flags());
        FlagTarget target = flags.get(normalizedFlagKey);
        if (target == null) {
            return EvaluationResult.error(
                    normalizedFlagKey,
                    ErrorCode.UNKNOWN_FLAG);
        }

        try {
            return evaluateFlag(
                    target,
                    context,
                    flags,
                    indexSegments(configuration.segments()),
                    new ArrayDeque<>());
        } catch (ConditionEvaluationException exception) {
            return EvaluationResult.error(
                    normalizedFlagKey,
                    exception.errorCode());
        }
    }

    public static void validate(TargetingConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration is required");
        if (configuration.flags().size() > MAX_FLAGS
                || configuration.segments().size() > MAX_SEGMENTS) {
            throw validation(ErrorCode.LIMIT_EXCEEDED,
                    "configuration exceeds flag or segment limits");
        }

        Map<String, FlagTarget> flags = indexFlags(configuration.flags());
        Map<String, Segment> segments = indexSegments(configuration.segments());
        for (Segment segment : segments.values()) {
            validateConditions(segment.conditions(), segments);
        }
        validateSegmentGraph(segments);

        for (FlagTarget flag : flags.values()) {
            validateFlag(flag, flags, segments);
        }
        validatePrerequisiteGraph(flags);
    }

    private static EvaluationResult evaluateFlag(
            FlagTarget flag,
            EvaluationContext context,
            Map<String, FlagTarget> flags,
            Map<String, Segment> segments,
            Deque<String> flagStack) {
        if (flagStack.size() >= MAX_GRAPH_DEPTH || flagStack.contains(flag.key())) {
            throw new ConditionEvaluationException(ErrorCode.CYCLIC_PREREQUISITE);
        }
        flagStack.push(flag.key());
        try {
            for (Prerequisite prerequisite : flag.prerequisites()) {
                FlagTarget requiredFlag = flags.get(prerequisite.flagKey());
                if (requiredFlag == null) {
                    throw new ConditionEvaluationException(
                            ErrorCode.UNKNOWN_PREREQUISITE);
                }
                EvaluationResult requiredResult = evaluateFlag(
                        requiredFlag,
                        context,
                        flags,
                        segments,
                        flagStack);
                if (requiredResult.reason() == EvaluationReason.ERROR) {
                    return EvaluationResult.error(
                            flag.key(),
                            requiredResult.errorCode());
                }
                if (!prerequisite.expectedVariant().equals(
                        requiredResult.variantKey())) {
                    return new EvaluationResult(
                            flag.key(),
                            flag.defaultVariant(),
                            EvaluationReason.PREREQUISITE_FAILED,
                            null,
                            prerequisite.flagKey(),
                            ErrorCode.NONE);
                }
            }

            for (TargetingRule rule : orderedRules(flag.rules())) {
                if (matchesAll(rule.conditions(), context, segments)) {
                    return new EvaluationResult(
                            flag.key(),
                            rule.variantKey(),
                            EvaluationReason.TARGETING_MATCH,
                            rule.key(),
                            null,
                            ErrorCode.NONE);
                }
            }

            return new EvaluationResult(
                    flag.key(),
                    flag.defaultVariant(),
                    EvaluationReason.DEFAULT,
                    null,
                    null,
                    ErrorCode.NONE);
        } finally {
            flagStack.pop();
        }
    }

    private static boolean matchesAll(
            List<Condition> conditions,
            EvaluationContext context,
            Map<String, Segment> segments) {
        Set<String> segmentStack = new LinkedHashSet<>();
        for (Condition condition : conditions) {
            if (!matches(condition, context, segments, segmentStack)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(
            Condition condition,
            EvaluationContext context,
            Map<String, Segment> segments,
            Set<String> segmentStack) {
        if (condition instanceof EqualityCondition equality) {
            AttributeValue actual = context.attributes().get(equality.attribute());
            if (actual == null) {
                return false;
            }
            return equalValues(actual, equality.expected());
        }
        if (condition instanceof StringSetCondition membership) {
            AttributeValue actual = context.attributes().get(membership.attribute());
            if (actual == null) {
                return false;
            }
            if (!(actual instanceof StringValue stringValue)) {
                throw new ConditionEvaluationException(
                        ErrorCode.ATTRIBUTE_TYPE_MISMATCH);
            }
            return membership.values().contains(stringValue.value());
        }
        if (condition instanceof NumericCondition numeric) {
            AttributeValue actual = context.attributes().get(numeric.attribute());
            if (actual == null) {
                return false;
            }
            if (!(actual instanceof NumberValue numberValue)) {
                throw new ConditionEvaluationException(
                        ErrorCode.ATTRIBUTE_TYPE_MISMATCH);
            }
            int comparison = numberValue.value().compareTo(numeric.operand());
            return numeric.operator().matches(comparison);
        }
        if (condition instanceof SemanticVersionCondition version) {
            AttributeValue actual = context.attributes().get(version.attribute());
            if (actual == null) {
                return false;
            }
            if (!(actual instanceof StringValue stringValue)) {
                throw new ConditionEvaluationException(
                        ErrorCode.ATTRIBUTE_TYPE_MISMATCH);
            }
            SemanticVersion actualVersion = SemanticVersion.parseForEvaluation(
                    stringValue.value());
            int comparison = actualVersion.compareTo(version.parsedOperand());
            return version.operator().matches(comparison);
        }
        if (condition instanceof SegmentCondition segmentCondition) {
            boolean member = matchesSegment(
                    segmentCondition.segmentKey(),
                    context,
                    segments,
                    segmentStack);
            return segmentCondition.negated() != member;
        }
        throw new ConditionEvaluationException(ErrorCode.INVALID_CONFIGURATION);
    }

    private static boolean matchesSegment(
            String segmentKey,
            EvaluationContext context,
            Map<String, Segment> segments,
            Set<String> segmentStack) {
        Segment segment = segments.get(segmentKey);
        if (segment == null) {
            throw new ConditionEvaluationException(ErrorCode.UNKNOWN_SEGMENT);
        }
        if (segmentStack.size() >= MAX_GRAPH_DEPTH
                || !segmentStack.add(segmentKey)) {
            throw new ConditionEvaluationException(ErrorCode.CYCLIC_SEGMENT);
        }
        try {
            if (segment.excludedTargetingKeys().contains(context.targetingKey())) {
                return false;
            }
            if (segment.includedTargetingKeys().contains(context.targetingKey())) {
                return true;
            }
            if (segment.conditions().isEmpty()) {
                return false;
            }
            for (Condition condition : segment.conditions()) {
                if (!matches(condition, context, segments, segmentStack)) {
                    return false;
                }
            }
            return true;
        } finally {
            segmentStack.remove(segmentKey);
        }
    }

    private static boolean equalValues(
            AttributeValue actual,
            AttributeValue expected) {
        if (actual instanceof NumberValue actualNumber
                && expected instanceof NumberValue expectedNumber) {
            return actualNumber.value().compareTo(expectedNumber.value()) == 0;
        }
        if (actual.getClass() != expected.getClass()) {
            throw new ConditionEvaluationException(
                    ErrorCode.ATTRIBUTE_TYPE_MISMATCH);
        }
        return actual.equals(expected);
    }

    private static List<TargetingRule> orderedRules(List<TargetingRule> rules) {
        return rules.stream()
                .sorted(Comparator.comparingInt(TargetingRule::priority))
                .toList();
    }

    private static void validateFlag(
            FlagTarget flag,
            Map<String, FlagTarget> flags,
            Map<String, Segment> segments) {
        if (flag.rules().size() > MAX_RULES_PER_FLAG) {
            throw validation(ErrorCode.LIMIT_EXCEEDED,
                    "flag exceeds the rule limit: " + flag.key());
        }
        if (!flag.variants().contains(flag.defaultVariant())) {
            throw validation(ErrorCode.UNKNOWN_VARIANT,
                    "default variant is not declared: " + flag.key());
        }

        Set<String> ruleKeys = new HashSet<>();
        Set<Integer> priorities = new HashSet<>();
        for (TargetingRule rule : flag.rules()) {
            if (!ruleKeys.add(rule.key())) {
                throw validation(ErrorCode.DUPLICATE_RULE_KEY,
                        "duplicate rule key: " + rule.key());
            }
            if (!priorities.add(rule.priority())) {
                throw validation(ErrorCode.DUPLICATE_RULE_PRIORITY,
                        "duplicate rule priority on flag: " + flag.key());
            }
            if (!flag.variants().contains(rule.variantKey())) {
                throw validation(ErrorCode.UNKNOWN_VARIANT,
                        "rule variant is not declared: " + rule.variantKey());
            }
            validateConditions(rule.conditions(), segments);
        }

        Set<String> prerequisiteKeys = new HashSet<>();
        for (Prerequisite prerequisite : flag.prerequisites()) {
            if (!prerequisiteKeys.add(prerequisite.flagKey())) {
                throw validation(ErrorCode.DUPLICATE_PREREQUISITE,
                        "duplicate prerequisite: " + prerequisite.flagKey());
            }
            FlagTarget requiredFlag = flags.get(prerequisite.flagKey());
            if (requiredFlag == null) {
                throw validation(ErrorCode.UNKNOWN_PREREQUISITE,
                        "unknown prerequisite: " + prerequisite.flagKey());
            }
            if (!requiredFlag.variants().contains(
                    prerequisite.expectedVariant())) {
                throw validation(ErrorCode.UNKNOWN_VARIANT,
                        "unknown prerequisite variant: "
                                + prerequisite.expectedVariant());
            }
        }
    }

    private static void validateConditions(
            List<Condition> conditions,
            Map<String, Segment> segments) {
        if (conditions.size() > MAX_CONDITIONS) {
            throw validation(ErrorCode.LIMIT_EXCEEDED,
                    "condition list exceeds the configured limit");
        }
        for (Condition condition : conditions) {
            if (condition instanceof SegmentCondition segmentCondition
                    && !segments.containsKey(segmentCondition.segmentKey())) {
                throw validation(ErrorCode.UNKNOWN_SEGMENT,
                        "unknown segment: " + segmentCondition.segmentKey());
            }
        }
    }

    private static Map<String, FlagTarget> indexFlags(List<FlagTarget> flags) {
        Map<String, FlagTarget> indexed = new LinkedHashMap<>();
        for (FlagTarget flag : flags) {
            if (indexed.putIfAbsent(flag.key(), flag) != null) {
                throw validation(ErrorCode.DUPLICATE_FLAG_KEY,
                        "duplicate flag key: " + flag.key());
            }
        }
        return Map.copyOf(indexed);
    }

    private static Map<String, Segment> indexSegments(List<Segment> segments) {
        Map<String, Segment> indexed = new LinkedHashMap<>();
        for (Segment segment : segments) {
            if (indexed.putIfAbsent(segment.key(), segment) != null) {
                throw validation(ErrorCode.DUPLICATE_SEGMENT_KEY,
                        "duplicate segment key: " + segment.key());
            }
        }
        return Map.copyOf(indexed);
    }

    private static void validatePrerequisiteGraph(Map<String, FlagTarget> flags) {
        Map<String, VisitState> states = new HashMap<>();
        for (String flagKey : flags.keySet()) {
            visitFlag(flagKey, flags, states, new ArrayDeque<>());
        }
    }

    private static void visitFlag(
            String flagKey,
            Map<String, FlagTarget> flags,
            Map<String, VisitState> states,
            Deque<String> path) {
        VisitState state = states.get(flagKey);
        if (state == VisitState.VISITED) {
            return;
        }
        if (state == VisitState.VISITING) {
            throw validation(ErrorCode.CYCLIC_PREREQUISITE,
                    "cyclic prerequisite graph: " + formatCycle(path, flagKey));
        }
        if (path.size() >= MAX_GRAPH_DEPTH) {
            throw validation(ErrorCode.LIMIT_EXCEEDED,
                    "prerequisite graph exceeds the depth limit");
        }

        states.put(flagKey, VisitState.VISITING);
        path.push(flagKey);
        for (Prerequisite prerequisite : flags.get(flagKey).prerequisites()) {
            visitFlag(prerequisite.flagKey(), flags, states, path);
        }
        path.pop();
        states.put(flagKey, VisitState.VISITED);
    }

    private static void validateSegmentGraph(Map<String, Segment> segments) {
        Map<String, VisitState> states = new HashMap<>();
        for (String segmentKey : segments.keySet()) {
            visitSegment(segmentKey, segments, states, new ArrayDeque<>());
        }
    }

    private static void visitSegment(
            String segmentKey,
            Map<String, Segment> segments,
            Map<String, VisitState> states,
            Deque<String> path) {
        VisitState state = states.get(segmentKey);
        if (state == VisitState.VISITED) {
            return;
        }
        if (state == VisitState.VISITING) {
            throw validation(ErrorCode.CYCLIC_SEGMENT,
                    "cyclic segment graph: " + formatCycle(path, segmentKey));
        }
        if (path.size() >= MAX_GRAPH_DEPTH) {
            throw validation(ErrorCode.LIMIT_EXCEEDED,
                    "segment graph exceeds the depth limit");
        }

        states.put(segmentKey, VisitState.VISITING);
        path.push(segmentKey);
        for (Condition condition : segments.get(segmentKey).conditions()) {
            if (condition instanceof SegmentCondition reference) {
                visitSegment(reference.segmentKey(), segments, states, path);
            }
        }
        path.pop();
        states.put(segmentKey, VisitState.VISITED);
    }

    private static String formatCycle(Deque<String> path, String repeatedKey) {
        List<String> cycle = new ArrayList<>(path);
        java.util.Collections.reverse(cycle);
        cycle.add(repeatedKey);
        return String.join(" -> ", cycle);
    }

    private static String normalizeKey(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " is required");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!KEY_PATTERN.matcher(normalized).matches()) {
            throw validation(ErrorCode.INVALID_KEY,
                    fieldName + " has an invalid format");
        }
        return normalized;
    }

    private static String normalizeTargetingKey(String value) {
        Objects.requireNonNull(value, "targetingKey is required");
        if (value.isBlank()) {
            throw validation(ErrorCode.INVALID_TARGETING_KEY,
                    "targetingKey cannot be blank");
        }
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    private static Set<String> normalizeKeys(
            Set<String> values,
            String fieldName) {
        Objects.requireNonNull(values, fieldName + " is required");
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(normalizeKey(value, fieldName));
        }
        return Set.copyOf(normalized);
    }

    private static Set<String> normalizeTargetingKeys(Set<String> values) {
        Objects.requireNonNull(values, "targeting keys are required");
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(normalizeTargetingKey(value));
        }
        return Set.copyOf(normalized);
    }

    private static List<Condition> copyConditions(List<Condition> conditions) {
        Objects.requireNonNull(conditions, "conditions are required");
        return List.copyOf(conditions);
    }

    private static TargetingValidationException validation(
            ErrorCode errorCode,
            String message) {
        return new TargetingValidationException(errorCode, message);
    }

    private enum VisitState {
        VISITING,
        VISITED
    }

    public sealed interface AttributeValue
            permits StringValue, NumberValue, BooleanValue {
    }

    public record StringValue(String value) implements AttributeValue {
        public StringValue {
            Objects.requireNonNull(value, "string value is required");
        }
    }

    public record NumberValue(BigDecimal value) implements AttributeValue {
        public NumberValue {
            Objects.requireNonNull(value, "number value is required");
        }
    }

    public record BooleanValue(boolean value) implements AttributeValue {
    }

    public sealed interface Condition permits EqualityCondition,
            StringSetCondition, NumericCondition,
            SemanticVersionCondition, SegmentCondition {
    }

    public record EqualityCondition(
            String attribute,
            AttributeValue expected) implements Condition {
        public EqualityCondition {
            attribute = normalizeKey(attribute, "attribute");
            Objects.requireNonNull(expected, "expected value is required");
        }
    }

    public record StringSetCondition(
            String attribute,
            Set<String> values) implements Condition {
        public StringSetCondition {
            attribute = normalizeKey(attribute, "attribute");
            Objects.requireNonNull(values, "values are required");
            if (values.isEmpty()) {
                throw validation(ErrorCode.EMPTY_SET,
                        "membership values cannot be empty");
            }
            values = Set.copyOf(values);
        }
    }

    public record NumericCondition(
            String attribute,
            NumericOperator operator,
            BigDecimal operand) implements Condition {
        public NumericCondition {
            attribute = normalizeKey(attribute, "attribute");
            Objects.requireNonNull(operator, "operator is required");
            Objects.requireNonNull(operand, "operand is required");
        }
    }

    public record SemanticVersionCondition(
            String attribute,
            VersionOperator operator,
            String operand) implements Condition {
        public SemanticVersionCondition {
            attribute = normalizeKey(attribute, "attribute");
            Objects.requireNonNull(operator, "operator is required");
            Objects.requireNonNull(operand, "operand is required");
            SemanticVersion.parseForConfiguration(operand);
        }

        private SemanticVersion parsedOperand() {
            return SemanticVersion.parseForConfiguration(operand);
        }
    }

    public record SegmentCondition(
            String segmentKey,
            boolean negated) implements Condition {
        public SegmentCondition {
            segmentKey = normalizeKey(segmentKey, "segmentKey");
        }
    }

    public enum NumericOperator {
        EQUAL {
            @Override
            boolean matches(int comparison) {
                return comparison == 0;
            }
        },
        LESS_THAN {
            @Override
            boolean matches(int comparison) {
                return comparison < 0;
            }
        },
        LESS_THAN_OR_EQUAL {
            @Override
            boolean matches(int comparison) {
                return comparison <= 0;
            }
        },
        GREATER_THAN {
            @Override
            boolean matches(int comparison) {
                return comparison > 0;
            }
        },
        GREATER_THAN_OR_EQUAL {
            @Override
            boolean matches(int comparison) {
                return comparison >= 0;
            }
        };

        abstract boolean matches(int comparison);
    }

    public enum VersionOperator {
        EQUAL {
            @Override
            boolean matches(int comparison) {
                return comparison == 0;
            }
        },
        LESS_THAN {
            @Override
            boolean matches(int comparison) {
                return comparison < 0;
            }
        },
        LESS_THAN_OR_EQUAL {
            @Override
            boolean matches(int comparison) {
                return comparison <= 0;
            }
        },
        GREATER_THAN {
            @Override
            boolean matches(int comparison) {
                return comparison > 0;
            }
        },
        GREATER_THAN_OR_EQUAL {
            @Override
            boolean matches(int comparison) {
                return comparison >= 0;
            }
        };

        abstract boolean matches(int comparison);
    }

    public record EvaluationContext(
            String targetingKey,
            Map<String, AttributeValue> attributes) {
        public EvaluationContext {
            targetingKey = normalizeTargetingKey(targetingKey);
            Objects.requireNonNull(attributes, "attributes are required");
            if (attributes.size() > MAX_ATTRIBUTES) {
                throw validation(ErrorCode.LIMIT_EXCEEDED,
                        "evaluation context exceeds the attribute limit");
            }
            Map<String, AttributeValue> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, AttributeValue> entry : attributes.entrySet()) {
                String key = normalizeKey(entry.getKey(), "attribute");
                AttributeValue value = Objects.requireNonNull(
                        entry.getValue(),
                        "attribute value is required");
                if (normalized.putIfAbsent(key, value) != null) {
                    throw validation(ErrorCode.DUPLICATE_ATTRIBUTE,
                            "duplicate normalized attribute: " + key);
                }
            }
            attributes = Map.copyOf(normalized);
        }
    }

    public record Segment(
            String key,
            Set<String> includedTargetingKeys,
            Set<String> excludedTargetingKeys,
            List<Condition> conditions) {
        public Segment {
            key = normalizeKey(key, "segmentKey");
            includedTargetingKeys = normalizeTargetingKeys(
                    includedTargetingKeys);
            excludedTargetingKeys = normalizeTargetingKeys(
                    excludedTargetingKeys);
            conditions = copyConditions(conditions);
        }
    }

    public record TargetingRule(
            String key,
            int priority,
            List<Condition> conditions,
            String variantKey) {
        public TargetingRule {
            key = normalizeKey(key, "ruleKey");
            if (priority < 0) {
                throw validation(ErrorCode.INVALID_PRIORITY,
                        "priority cannot be negative");
            }
            conditions = copyConditions(conditions);
            variantKey = normalizeKey(variantKey, "variantKey");
        }
    }

    public record Prerequisite(
            String flagKey,
            String expectedVariant) {
        public Prerequisite {
            flagKey = normalizeKey(flagKey, "flagKey");
            expectedVariant = normalizeKey(
                    expectedVariant,
                    "expectedVariant");
        }
    }

    public record FlagTarget(
            String key,
            Set<String> variants,
            String defaultVariant,
            List<Prerequisite> prerequisites,
            List<TargetingRule> rules) {
        public FlagTarget {
            key = normalizeKey(key, "flagKey");
            variants = normalizeKeys(variants, "variantKey");
            if (variants.isEmpty()) {
                throw validation(ErrorCode.EMPTY_SET,
                        "flag variants cannot be empty");
            }
            defaultVariant = normalizeKey(defaultVariant, "defaultVariant");
            prerequisites = List.copyOf(Objects.requireNonNull(
                    prerequisites,
                    "prerequisites are required"));
            rules = List.copyOf(Objects.requireNonNull(
                    rules,
                    "rules are required"));
        }
    }

    public record TargetingConfiguration(
            List<FlagTarget> flags,
            List<Segment> segments) {
        public TargetingConfiguration {
            flags = List.copyOf(Objects.requireNonNull(
                    flags,
                    "flags are required"));
            segments = List.copyOf(Objects.requireNonNull(
                    segments,
                    "segments are required"));
        }
    }

    public enum EvaluationReason {
        TARGETING_MATCH,
        DEFAULT,
        PREREQUISITE_FAILED,
        ERROR
    }

    public enum ErrorCode {
        NONE,
        UNKNOWN_FLAG,
        DUPLICATE_FLAG_KEY,
        DUPLICATE_SEGMENT_KEY,
        DUPLICATE_RULE_KEY,
        DUPLICATE_RULE_PRIORITY,
        DUPLICATE_PREREQUISITE,
        DUPLICATE_ATTRIBUTE,
        UNKNOWN_VARIANT,
        UNKNOWN_SEGMENT,
        UNKNOWN_PREREQUISITE,
        CYCLIC_SEGMENT,
        CYCLIC_PREREQUISITE,
        ATTRIBUTE_TYPE_MISMATCH,
        INVALID_SEMANTIC_VERSION,
        INVALID_CONFIGURATION,
        INVALID_KEY,
        INVALID_TARGETING_KEY,
        INVALID_PRIORITY,
        EMPTY_SET,
        LIMIT_EXCEEDED
    }

    public record EvaluationResult(
            String flagKey,
            String variantKey,
            EvaluationReason reason,
            String matchedRuleKey,
            String failedPrerequisiteKey,
            ErrorCode errorCode) {
        public EvaluationResult {
            Objects.requireNonNull(flagKey, "flagKey is required");
            Objects.requireNonNull(reason, "reason is required");
            Objects.requireNonNull(errorCode, "errorCode is required");
        }

        private static EvaluationResult error(
                String flagKey,
                ErrorCode errorCode) {
            return new EvaluationResult(
                    flagKey,
                    null,
                    EvaluationReason.ERROR,
                    null,
                    null,
                    errorCode);
        }
    }

    public static final class TargetingValidationException
            extends IllegalArgumentException {
        private final ErrorCode errorCode;

        private TargetingValidationException(
                ErrorCode errorCode,
                String message) {
            super(message);
            this.errorCode = Objects.requireNonNull(
                    errorCode,
                    "errorCode is required");
        }

        public ErrorCode errorCode() {
            return errorCode;
        }
    }

    private static final class ConditionEvaluationException
            extends RuntimeException {
        private final ErrorCode errorCode;

        private ConditionEvaluationException(ErrorCode errorCode) {
            super(errorCode.name());
            this.errorCode = errorCode;
        }

        private ErrorCode errorCode() {
            return errorCode;
        }
    }

    private record SemanticVersion(
            BigInteger major,
            BigInteger minor,
            BigInteger patch,
            List<String> prerelease) implements Comparable<SemanticVersion> {

        private static SemanticVersion parseForConfiguration(String value) {
            try {
                return parse(value);
            } catch (IllegalArgumentException exception) {
                throw validation(ErrorCode.INVALID_SEMANTIC_VERSION,
                        "invalid semantic version: " + value);
            }
        }

        private static SemanticVersion parseForEvaluation(String value) {
            try {
                return parse(value);
            } catch (IllegalArgumentException exception) {
                throw new ConditionEvaluationException(
                        ErrorCode.INVALID_SEMANTIC_VERSION);
            }
        }

        private static SemanticVersion parse(String value) {
            Objects.requireNonNull(value, "semantic version is required");
            java.util.regex.Matcher matcher = SEMVER_PATTERN.matcher(value);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("invalid semantic version");
            }
            List<String> prerelease = matcher.group(4) == null
                    ? List.of()
                    : List.of(matcher.group(4).split("\\."));
            for (String identifier : prerelease) {
                if (isNumeric(identifier)
                        && identifier.length() > 1
                        && identifier.startsWith("0")) {
                    throw new IllegalArgumentException(
                            "numeric prerelease identifier has a leading zero");
                }
            }
            return new SemanticVersion(
                    new BigInteger(matcher.group(1)),
                    new BigInteger(matcher.group(2)),
                    new BigInteger(matcher.group(3)),
                    prerelease);
        }

        @Override
        public int compareTo(SemanticVersion other) {
            int comparison = major.compareTo(other.major);
            if (comparison != 0) {
                return comparison;
            }
            comparison = minor.compareTo(other.minor);
            if (comparison != 0) {
                return comparison;
            }
            comparison = patch.compareTo(other.patch);
            if (comparison != 0) {
                return comparison;
            }
            if (prerelease.isEmpty() && other.prerelease.isEmpty()) {
                return 0;
            }
            if (prerelease.isEmpty()) {
                return 1;
            }
            if (other.prerelease.isEmpty()) {
                return -1;
            }
            int limit = Math.min(prerelease.size(), other.prerelease.size());
            for (int index = 0; index < limit; index++) {
                comparison = compareIdentifier(
                        prerelease.get(index),
                        other.prerelease.get(index));
                if (comparison != 0) {
                    return comparison;
                }
            }
            return Integer.compare(prerelease.size(), other.prerelease.size());
        }

        private static int compareIdentifier(String left, String right) {
            boolean leftNumeric = isNumeric(left);
            boolean rightNumeric = isNumeric(right);
            if (leftNumeric && rightNumeric) {
                return new BigInteger(left).compareTo(new BigInteger(right));
            }
            if (leftNumeric) {
                return -1;
            }
            if (rightNumeric) {
                return 1;
            }
            return left.compareTo(right);
        }

        private static boolean isNumeric(String value) {
            return value.chars().allMatch(Character::isDigit);
        }
    }
}
