package io.github.viniciusssantos.flagforge.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.CredentialScope;
import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.SdkPrincipal;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationApi.AttributeType;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationApi.ErrorCode;
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
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.BooleanValue;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.EqualityCondition;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.FlagTarget;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.Prerequisite;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.TargetingConfiguration;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.TargetingRule;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationServiceTests {

    private static final UUID ORGANIZATION_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222");
    private static final UUID ENVIRONMENT_ID = UUID.fromString(
            "33333333-3333-3333-3333-333333333333");
    private static final SdkPrincipal PRINCIPAL = new SdkPrincipal(
            UUID.fromString("44444444-4444-4444-4444-444444444444"),
            ORGANIZATION_ID,
            ENVIRONMENT_ID,
            CredentialScope.EVALUATE);

    @Test
    void exposesTargetingDefaultAndPrerequisiteReasons() {
        FlagTarget prerequisite = new FlagTarget(
                "platform-ready",
                Set.of("off", "on"),
                "off",
                List.of(),
                List.of(new TargetingRule(
                        "ready",
                        10,
                        List.of(new EqualityCondition(
                                "ready",
                                new BooleanValue(true))),
                        "on")));
        FlagTarget checkout = new FlagTarget(
                "checkout-v2",
                Set.of("off", "on"),
                "off",
                List.of(new Prerequisite("platform-ready", "on")),
                List.of(new TargetingRule(
                        "beta",
                        10,
                        List.of(new EqualityCondition(
                                "beta",
                                new BooleanValue(true))),
                        "on")));
        EvaluationSnapshot snapshot = snapshot(
                true,
                false,
                new TargetingConfiguration(
                        List.of(checkout, prerequisite),
                        List.of()),
                null);
        EvaluationService service = service(Optional.of(snapshot));

        Response targetingMatch = service.evaluate(
                PRINCIPAL,
                "checkout-v2",
                request(true, Map.of(
                        "ready", booleanAttribute(true),
                        "beta", booleanAttribute(true))));
        Response defaultResult = service.evaluate(
                PRINCIPAL,
                "checkout-v2",
                request(true, Map.of(
                        "ready", booleanAttribute(true),
                        "beta", booleanAttribute(false))));
        Response prerequisiteFailed = service.evaluate(
                PRINCIPAL,
                "checkout-v2",
                request(true, Map.of(
                        "ready", booleanAttribute(false),
                        "beta", booleanAttribute(true))));

        assertThat(targetingMatch.reason()).isEqualTo(Reason.TARGETING_MATCH);
        assertThat(targetingMatch.variant()).isEqualTo("on");
        assertThat(targetingMatch.matchedRuleKey()).isEqualTo("beta");
        assertThat(defaultResult.reason()).isEqualTo(Reason.DEFAULT);
        assertThat(defaultResult.variant()).isEqualTo("off");
        assertThat(prerequisiteFailed.reason())
                .isEqualTo(Reason.PREREQUISITE_FAILED);
        assertThat(prerequisiteFailed.failedPrerequisiteKey())
                .isEqualTo("platform-ready");
    }

    @Test
    void returnsCallerFallbackForUnknownDisabledAndTypeMismatch() {
        EvaluationService unknownService = service(Optional.empty());
        Response unknown = unknownService.evaluate(
                PRINCIPAL,
                "missing-flag",
                request(false, Map.of()));

        EvaluationSnapshot disabledSnapshot = new EvaluationSnapshot(
                ORGANIZATION_ID,
                PROJECT_ID,
                ENVIRONMENT_ID,
                "version-disabled",
                false,
                false,
                ValueType.BOOLEAN,
                "off",
                booleanVariants(),
                booleanConfiguration(),
                null);
        Response disabled = service(Optional.of(disabledSnapshot)).evaluate(
                PRINCIPAL,
                "checkout-v2",
                request(true, Map.of()));
        Request disabledWrongType = new Request(
                ValueType.STRING,
                JsonNodeFactory.instance.textNode("fallback"),
                "sensitive-user-123",
                Map.of());
        Response disabledMismatch = service(Optional.of(disabledSnapshot)).evaluate(
                PRINCIPAL,
                "checkout-v2",
                disabledWrongType);

        EvaluationSnapshot stringSnapshot = new EvaluationSnapshot(
                ORGANIZATION_ID,
                PROJECT_ID,
                ENVIRONMENT_ID,
                "version-string",
                true,
                false,
                ValueType.STRING,
                "control",
                Map.of(
                        "control",
                        new VariantValue(ValueType.STRING, "control")),
                new TargetingConfiguration(
                        List.of(new FlagTarget(
                                "checkout-v2",
                                Set.of("control"),
                                "control",
                                List.of(),
                                List.of())),
                        List.of()),
                null);
        Response mismatch = service(Optional.of(stringSnapshot)).evaluate(
                PRINCIPAL,
                "checkout-v2",
                request(false, Map.of()));

        assertThat(unknown.value()).isEqualTo(false);
        assertThat(unknown.reason()).isEqualTo(Reason.ERROR);
        assertThat(unknown.error().code()).isEqualTo(ErrorCode.FLAG_NOT_FOUND);
        assertThat(disabled.value()).isEqualTo(true);
        assertThat(disabled.reason()).isEqualTo(Reason.DISABLED);
        assertThat(disabled.error().code()).isEqualTo(ErrorCode.NONE);
        assertThat(disabledMismatch.reason()).isEqualTo(Reason.ERROR);
        assertThat(disabledMismatch.value()).isEqualTo("fallback");
        assertThat(disabledMismatch.error().code())
                .isEqualTo(ErrorCode.TYPE_MISMATCH);
        assertThat(mismatch.value()).isEqualTo(false);
        assertThat(mismatch.error().code()).isEqualTo(ErrorCode.TYPE_MISMATCH);
    }

    @Test
    void producesStableSplitAndReportsBucket() {
        PercentageSplit split = new PercentageSplit(
                "checkout-cohort-v1",
                List.of(
                        new SplitAllocation("off", 50_000),
                        new SplitAllocation("on", 50_000)));
        EvaluationService service = service(Optional.of(snapshot(
                true,
                false,
                booleanConfiguration(),
                split)));
        Request request = request(false, Map.of());

        Response first = service.evaluate(
                PRINCIPAL,
                "checkout-v2",
                request);
        Response repeated = service.evaluate(
                PRINCIPAL,
                "checkout-v2",
                request);

        assertThat(first.reason()).isEqualTo(Reason.SPLIT);
        assertThat(first.bucket()).isBetween(0, 99_999);
        assertThat(repeated).isEqualTo(first);
    }

    @Test
    void marksLastKnownGoodResultsAsStaleWithoutLosingSourceReason() {
        EvaluationSnapshot staleSnapshot = snapshot(
                true,
                true,
                booleanConfiguration(),
                null);

        Response response = service(Optional.of(staleSnapshot)).evaluate(
                PRINCIPAL,
                "checkout-v2",
                request(false, Map.of()));

        assertThat(response.reason()).isEqualTo(Reason.STALE);
        assertThat(response.sourceReason()).isEqualTo(Reason.DEFAULT.name());
        assertThat(response.stale()).isTrue();
        assertThat(response.configurationVersion()).isEqualTo("version-7");
    }

    @Test
    void rejectsFallbackAttributesAndOversizedTargetingKeys() {
        EvaluationService service = service(Optional.of(snapshot(
                true,
                false,
                booleanConfiguration(),
                null)));
        Request invalidFallback = new Request(
                ValueType.BOOLEAN,
                JsonNodeFactory.instance.textNode("false"),
                "sensitive-user-123",
                Map.of());
        Request invalidAttribute = new Request(
                ValueType.BOOLEAN,
                JsonNodeFactory.instance.booleanNode(false),
                "sensitive-user-123",
                Map.of(
                        "age",
                        new TypedAttribute(
                                AttributeType.NUMBER,
                                JsonNodeFactory.instance.textNode("18"))));
        Request oversizedTargetingKey = new Request(
                ValueType.BOOLEAN,
                JsonNodeFactory.instance.booleanNode(false),
                "x".repeat(1_025),
                Map.of());

        assertThatThrownBy(() -> service.evaluate(
                PRINCIPAL,
                "checkout-v2",
                invalidFallback))
                .isInstanceOf(EvaluationRequestException.class)
                .hasMessageContaining("exact BOOLEAN");
        assertThatThrownBy(() -> service.evaluate(
                PRINCIPAL,
                "checkout-v2",
                invalidAttribute))
                .isInstanceOf(EvaluationRequestException.class)
                .hasMessageContaining("declared type");
        assertThatThrownBy(() -> service.evaluate(
                PRINCIPAL,
                "checkout-v2",
                oversizedTargetingKey))
                .isInstanceOf(EvaluationRequestException.class)
                .hasMessageContaining("UTF-8 byte limit");
    }

    @Test
    void metricsUseOnlyBoundedEnumeratedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EvaluationService service = new EvaluationService(
                (principal, flagKey) -> Optional.empty(),
                new EvaluationMetrics(registry));

        service.evaluate(
                PRINCIPAL,
                "checkout-v2",
                request(false, Map.of()));

        assertThat(registry.getMeters()).hasSize(1);
        assertThat(registry.getMeters().getFirst().getId().getTags())
                .extracting(tag -> tag.getKey())
                .containsExactlyInAnyOrder(
                        "reason",
                        "value_type",
                        "error_code",
                        "stale");
        assertThat(registry.getMeters().getFirst().getId().getTags())
                .extracting(tag -> tag.getValue())
                .doesNotContain("sensitive-user-123", "checkout-v2");
    }

    private static EvaluationService service(
            Optional<EvaluationSnapshot> snapshot) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        return new EvaluationService(
                (principal, flagKey) -> snapshot,
                new EvaluationMetrics(registry));
    }

    private static EvaluationSnapshot snapshot(
            boolean enabled,
            boolean stale,
            TargetingConfiguration configuration,
            PercentageSplit split) {
        return new EvaluationSnapshot(
                ORGANIZATION_ID,
                PROJECT_ID,
                ENVIRONMENT_ID,
                "version-7",
                enabled,
                stale,
                ValueType.BOOLEAN,
                "off",
                booleanVariants(),
                configuration,
                split);
    }

    private static TargetingConfiguration booleanConfiguration() {
        return new TargetingConfiguration(
                List.of(new FlagTarget(
                        "checkout-v2",
                        Set.of("off", "on"),
                        "off",
                        List.of(),
                        List.of())),
                List.of());
    }

    private static Map<String, VariantValue> booleanVariants() {
        return Map.of(
                "off", new VariantValue(ValueType.BOOLEAN, false),
                "on", new VariantValue(ValueType.BOOLEAN, true));
    }

    private static Request request(
            boolean fallback,
            Map<String, TypedAttribute> attributes) {
        return new Request(
                ValueType.BOOLEAN,
                JsonNodeFactory.instance.booleanNode(fallback),
                "sensitive-user-123",
                attributes);
    }

    private static TypedAttribute booleanAttribute(boolean value) {
        return new TypedAttribute(
                AttributeType.BOOLEAN,
                JsonNodeFactory.instance.booleanNode(value));
    }
}
