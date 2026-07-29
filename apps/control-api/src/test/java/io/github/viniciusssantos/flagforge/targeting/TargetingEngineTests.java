package io.github.viniciusssantos.flagforge.targeting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.BooleanValue;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.EqualityCondition;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.ErrorCode;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.EvaluationContext;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.EvaluationReason;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.EvaluationResult;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.FlagTarget;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.NumberValue;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.NumericCondition;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.NumericOperator;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.Prerequisite;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.Segment;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.SegmentCondition;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.SemanticVersionCondition;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.StringSetCondition;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.StringValue;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.TargetingConfiguration;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.TargetingRule;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.TargetingValidationException;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.VersionOperator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class TargetingEngineTests {

    @Test
    void evaluatesRulesByPriorityAndFirstMatchWins() {
        FlagTarget flag = flag(
                "checkout-v2",
                "off",
                List.of(
                        rule("second-in-list", 20,
                                List.of(equalsString("country", "BR")),
                                "regional"),
                        rule("first-by-priority", 10,
                                List.of(equalsString("plan", "pro")),
                                "premium"),
                        rule("third", 30, List.of(), "fallback-rule")),
                List.of(),
                Set.of("off", "regional", "premium", "fallback-rule"));
        TargetingConfiguration configuration = configuration(flag);
        EvaluationContext context = context(
                "subject-1",
                Map.of(
                        "country", new StringValue("BR"),
                        "plan", new StringValue("pro")));

        EvaluationResult result = TargetingEngine.evaluate(
                configuration,
                "checkout-v2",
                context);

        assertThat(result.variantKey()).isEqualTo("premium");
        assertThat(result.matchedRuleKey()).isEqualTo("first-by-priority");
        assertThat(result.reason()).isEqualTo(EvaluationReason.TARGETING_MATCH);
    }

    @Test
    void supportsTypedEqualitySetNumericAndSemanticVersionConditions() {
        FlagTarget flag = flag(
                "typed",
                "off",
                List.of(rule(
                        "all-typed",
                        10,
                        List.of(
                                new EqualityCondition(
                                        "enabled",
                                        new BooleanValue(true)),
                                new StringSetCondition(
                                        "country",
                                        Set.of("BR", "AR")),
                                new NumericCondition(
                                        "score",
                                        NumericOperator.GREATER_THAN_OR_EQUAL,
                                        new BigDecimal("9.5")),
                                new SemanticVersionCondition(
                                        "app-version",
                                        VersionOperator.GREATER_THAN_OR_EQUAL,
                                        "2.1.0-rc.1")),
                        "on")),
                List.of(),
                Set.of("off", "on"));
        EvaluationContext context = context(
                "subject-2",
                Map.of(
                        "enabled", new BooleanValue(true),
                        "country", new StringValue("BR"),
                        "score", new NumberValue(new BigDecimal("9.50")),
                        "app-version", new StringValue("2.1.0+build.7")));

        EvaluationResult result = TargetingEngine.evaluate(
                configuration(flag),
                "typed",
                context);

        assertThat(result.variantKey()).isEqualTo("on");
        assertThat(result.reason()).isEqualTo(EvaluationReason.TARGETING_MATCH);
    }

    @Test
    void missingAttributesAreNoMatchAndUseTheDefaultVariant() {
        FlagTarget flag = flag(
                "checkout-v2",
                "off",
                List.of(rule(
                        "country",
                        10,
                        List.of(equalsString("country", "BR")),
                        "on")),
                List.of(),
                Set.of("off", "on"));

        EvaluationResult result = TargetingEngine.evaluate(
                configuration(flag),
                "checkout-v2",
                context("subject-3", Map.of()));

        assertThat(result.variantKey()).isEqualTo("off");
        assertThat(result.reason()).isEqualTo(EvaluationReason.DEFAULT);
        assertThat(result.errorCode()).isEqualTo(ErrorCode.NONE);
    }

    @Test
    void incompatibleAttributeTypesReturnAStableError() {
        FlagTarget flag = flag(
                "checkout-v2",
                "off",
                List.of(rule(
                        "adult",
                        10,
                        List.of(new NumericCondition(
                                "age",
                                NumericOperator.GREATER_THAN_OR_EQUAL,
                                new BigDecimal("18"))),
                        "on")),
                List.of(),
                Set.of("off", "on"));

        EvaluationResult result = TargetingEngine.evaluate(
                configuration(flag),
                "checkout-v2",
                context("subject-4", Map.of("age", new StringValue("18"))));

        assertThat(result.reason()).isEqualTo(EvaluationReason.ERROR);
        assertThat(result.errorCode())
                .isEqualTo(ErrorCode.ATTRIBUTE_TYPE_MISMATCH);
        assertThat(result.variantKey()).isNull();
    }

    @Test
    void invalidRuntimeSemanticVersionsReturnAStableError() {
        FlagTarget flag = flag(
                "mobile-app",
                "legacy",
                List.of(rule(
                        "modern",
                        10,
                        List.of(new SemanticVersionCondition(
                                "version",
                                VersionOperator.GREATER_THAN_OR_EQUAL,
                                "2.0.0")),
                        "modern")),
                List.of(),
                Set.of("legacy", "modern"));

        EvaluationResult result = TargetingEngine.evaluate(
                configuration(flag),
                "mobile-app",
                context("subject-5", Map.of(
                        "version",
                        new StringValue("v2"))));

        assertThat(result.reason()).isEqualTo(EvaluationReason.ERROR);
        assertThat(result.errorCode())
                .isEqualTo(ErrorCode.INVALID_SEMANTIC_VERSION);
    }

    @Test
    void semanticVersionOrderingFollowsPrereleaseAndBuildRules() {
        FlagTarget flag = flag(
                "semver",
                "old",
                List.of(
                        rule(
                                "release",
                                10,
                                List.of(new SemanticVersionCondition(
                                        "version",
                                        VersionOperator.EQUAL,
                                        "1.0.0")),
                                "release"),
                        rule(
                                "candidate",
                                20,
                                List.of(new SemanticVersionCondition(
                                        "version",
                                        VersionOperator.GREATER_THAN,
                                        "1.0.0-beta.11")),
                                "candidate")),
                List.of(),
                Set.of("old", "release", "candidate"));

        EvaluationResult buildMetadata = TargetingEngine.evaluate(
                configuration(flag),
                "semver",
                context("subject-6", Map.of(
                        "version",
                        new StringValue("1.0.0+build.99"))));
        EvaluationResult prerelease = TargetingEngine.evaluate(
                configuration(flag),
                "semver",
                context("subject-7", Map.of(
                        "version",
                        new StringValue("1.0.0-rc.1"))));

        assertThat(buildMetadata.variantKey()).isEqualTo("release");
        assertThat(prerelease.variantKey()).isEqualTo("candidate");
    }

    @Test
    void segmentExclusionWinsThenInclusionThenConditions() {
        Segment segment = new Segment(
                "beta-users",
                Set.of("included", "both"),
                Set.of("excluded", "both"),
                List.of(equalsString("country", "BR")));
        FlagTarget flag = flag(
                "checkout-v2",
                "off",
                List.of(rule(
                        "segment",
                        10,
                        List.of(new SegmentCondition("beta-users", false)),
                        "on")),
                List.of(),
                Set.of("off", "on"));
        TargetingConfiguration configuration = new TargetingConfiguration(
                List.of(flag),
                List.of(segment));

        EvaluationResult excluded = TargetingEngine.evaluate(
                configuration,
                "checkout-v2",
                context("both", Map.of("country", new StringValue("BR"))));
        EvaluationResult included = TargetingEngine.evaluate(
                configuration,
                "checkout-v2",
                context("included", Map.of("country", new StringValue("US"))));
        EvaluationResult condition = TargetingEngine.evaluate(
                configuration,
                "checkout-v2",
                context("dynamic", Map.of("country", new StringValue("BR"))));

        assertThat(excluded.variantKey()).isEqualTo("off");
        assertThat(included.variantKey()).isEqualTo("on");
        assertThat(condition.variantKey()).isEqualTo("on");
    }

    @Test
    void supportsNestedAndNegatedSegmentMembership() {
        Segment internal = new Segment(
                "internal",
                Set.of(),
                Set.of(),
                List.of(new StringSetCondition(
                        "email-domain",
                        Set.of("flagforge.dev", "example.com"))));
        Segment external = new Segment(
                "external",
                Set.of(),
                Set.of(),
                List.of(new SegmentCondition("internal", true)));
        FlagTarget flag = flag(
                "external-banner",
                "off",
                List.of(rule(
                        "external-segment",
                        10,
                        List.of(new SegmentCondition("external", false)),
                        "on")),
                List.of(),
                Set.of("off", "on"));
        TargetingConfiguration configuration = new TargetingConfiguration(
                List.of(flag),
                List.of(internal, external));

        EvaluationResult externalResult = TargetingEngine.evaluate(
                configuration,
                "external-banner",
                context("subject-8", Map.of(
                        "email-domain",
                        new StringValue("customer.com"))));
        EvaluationResult internalResult = TargetingEngine.evaluate(
                configuration,
                "external-banner",
                context("subject-9", Map.of(
                        "email-domain",
                        new StringValue("flagforge.dev"))));

        assertThat(externalResult.variantKey()).isEqualTo("on");
        assertThat(internalResult.variantKey()).isEqualTo("off");
    }

    @Test
    void evaluatesPrerequisitesBeforeTheTargetFlag() {
        FlagTarget platform = flag(
                "platform-ready",
                "off",
                List.of(rule(
                        "ready",
                        10,
                        List.of(new EqualityCondition(
                                "ready",
                                new BooleanValue(true))),
                        "on")),
                List.of(),
                Set.of("off", "on"));
        FlagTarget checkout = flag(
                "checkout-v2",
                "off",
                List.of(rule("eligible", 10, List.of(), "on")),
                List.of(new Prerequisite("platform-ready", "on")),
                Set.of("off", "on"));
        TargetingConfiguration configuration = new TargetingConfiguration(
                List.of(checkout, platform),
                List.of());

        EvaluationResult blocked = TargetingEngine.evaluate(
                configuration,
                "checkout-v2",
                context("subject-10", Map.of(
                        "ready",
                        new BooleanValue(false))));
        EvaluationResult enabled = TargetingEngine.evaluate(
                configuration,
                "checkout-v2",
                context("subject-11", Map.of(
                        "ready",
                        new BooleanValue(true))));

        assertThat(blocked.reason())
                .isEqualTo(EvaluationReason.PREREQUISITE_FAILED);
        assertThat(blocked.failedPrerequisiteKey())
                .isEqualTo("platform-ready");
        assertThat(blocked.variantKey()).isEqualTo("off");
        assertThat(enabled.variantKey()).isEqualTo("on");
    }

    @Test
    void rejectsDuplicatePrioritiesAndCyclicGraphsBeforeEvaluation() {
        FlagTarget duplicatePriority = flag(
                "duplicate",
                "off",
                List.of(
                        rule("one", 10, List.of(), "on"),
                        rule("two", 10, List.of(), "off")),
                List.of(),
                Set.of("off", "on"));
        TargetingValidationException duplicate = catchThrowableOfType(
                () -> TargetingEngine.validate(configuration(duplicatePriority)),
                TargetingValidationException.class);

        FlagTarget first = flag(
                "first",
                "off",
                List.of(),
                List.of(new Prerequisite("second", "on")),
                Set.of("off", "on"));
        FlagTarget second = flag(
                "second",
                "off",
                List.of(),
                List.of(new Prerequisite("first", "on")),
                Set.of("off", "on"));
        TargetingValidationException cycle = catchThrowableOfType(
                () -> TargetingEngine.validate(new TargetingConfiguration(
                        List.of(first, second),
                        List.of())),
                TargetingValidationException.class);

        assertThat(duplicate.errorCode())
                .isEqualTo(ErrorCode.DUPLICATE_RULE_PRIORITY);
        assertThat(cycle.errorCode())
                .isEqualTo(ErrorCode.CYCLIC_PREREQUISITE);
    }

    @Test
    void rejectsCyclicSegmentReferencesBeforeEvaluation() {
        Segment first = new Segment(
                "first",
                Set.of(),
                Set.of(),
                List.of(new SegmentCondition("second", false)));
        Segment second = new Segment(
                "second",
                Set.of(),
                Set.of(),
                List.of(new SegmentCondition("first", false)));
        FlagTarget flag = flag(
                "checkout-v2",
                "off",
                List.of(),
                List.of(),
                Set.of("off", "on"));

        TargetingValidationException exception = catchThrowableOfType(
                () -> TargetingEngine.validate(new TargetingConfiguration(
                        List.of(flag),
                        List.of(first, second))),
                TargetingValidationException.class);

        assertThat(exception.errorCode()).isEqualTo(ErrorCode.CYCLIC_SEGMENT);
    }

    @Test
    void fixedSeedPropertySweepProvesDeterminismAndTermination() {
        FlagTarget flag = flag(
                "checkout-v2",
                "off",
                List.of(
                        rule(
                                "enterprise",
                                10,
                                List.of(new StringSetCondition(
                                        "plan",
                                        Set.of("enterprise", "pro"))),
                                "on"),
                        rule(
                                "score",
                                20,
                                List.of(new NumericCondition(
                                        "score",
                                        NumericOperator.GREATER_THAN,
                                        new BigDecimal("50"))),
                                "review")),
                List.of(),
                Set.of("off", "on", "review"));
        TargetingConfiguration configuration = configuration(flag);
        SplittableRandom random = new SplittableRandom(0x11F0A6EL);
        List<EvaluationResult> firstPass = new ArrayList<>();
        List<EvaluationContext> contexts = new ArrayList<>();

        for (int index = 0; index < 10_000; index++) {
            EvaluationContext context = context(
                    "subject-" + index,
                    Map.of(
                            "plan",
                            new StringValue(index % 3 == 0
                                    ? "enterprise"
                                    : "free"),
                            "score",
                            new NumberValue(BigDecimal.valueOf(
                                    random.nextInt(101)))));
            contexts.add(context);
            firstPass.add(TargetingEngine.evaluate(
                    configuration,
                    "checkout-v2",
                    context));
        }

        for (int index = 0; index < contexts.size(); index++) {
            EvaluationResult repeated = TargetingEngine.evaluate(
                    configuration,
                    "checkout-v2",
                    contexts.get(index));
            assertThat(repeated).isEqualTo(firstPass.get(index));
        }
        assertThat(firstPass).allSatisfy(result ->
                assertThat(result.reason()).isNotEqualTo(EvaluationReason.ERROR));
    }

    @Test
    void normalizesKeysAndTargetingUnicodeWithoutCoercingValues() {
        Segment segment = new Segment(
                " BETA-USERS ",
                Set.of("José"),
                Set.of(),
                List.of());
        FlagTarget flag = flag(
                " CHECKOUT-V2 ",
                " OFF ",
                List.of(rule(
                        " SEGMENT ",
                        10,
                        List.of(new SegmentCondition(" BETA-USERS ", false)),
                        " ON ")),
                List.of(),
                Set.of(" OFF ", " ON "));
        TargetingConfiguration configuration = new TargetingConfiguration(
                List.of(flag),
                List.of(segment));

        EvaluationResult result = TargetingEngine.evaluate(
                configuration,
                "checkout-v2",
                context("José", Map.of()));

        assertThat(result.variantKey()).isEqualTo("on");
        assertThat(result.matchedRuleKey()).isEqualTo("segment");
    }

    private static EqualityCondition equalsString(
            String attribute,
            String expected) {
        return new EqualityCondition(attribute, new StringValue(expected));
    }

    private static TargetingRule rule(
            String key,
            int priority,
            List<TargetingEngine.Condition> conditions,
            String variant) {
        return new TargetingRule(key, priority, conditions, variant);
    }

    private static FlagTarget flag(
            String key,
            String defaultVariant,
            List<TargetingRule> rules,
            List<Prerequisite> prerequisites,
            Set<String> variants) {
        return new FlagTarget(
                key,
                variants,
                defaultVariant,
                prerequisites,
                rules);
    }

    private static TargetingConfiguration configuration(FlagTarget flag) {
        return new TargetingConfiguration(List.of(flag), List.of());
    }

    private static EvaluationContext context(
            String targetingKey,
            Map<String, TargetingEngine.AttributeValue> attributes) {
        return new EvaluationContext(targetingKey, attributes);
    }
}
