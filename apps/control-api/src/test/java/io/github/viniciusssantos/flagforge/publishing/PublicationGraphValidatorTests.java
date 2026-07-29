package io.github.viniciusssantos.flagforge.publishing;

import java.util.List;
import java.util.Set;

import io.github.viniciusssantos.flagforge.publishing.PublicationGraphValidator.PublicationGraphException;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedFlag;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedValueType;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedVariant;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.FlagTarget;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.Prerequisite;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.SegmentCondition;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.TargetingConfiguration;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.TargetingRule;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicationGraphValidatorTests {

    private final PublicationGraphValidator validator =
            new PublicationGraphValidator();

    @Test
    void canonicalizesFlagsPrerequisitesAndRulesDeterministically() {
        TargetingConfiguration requested = new TargetingConfiguration(
                List.of(
                        flag(
                                "payments-v2",
                                List.of(new Prerequisite("checkout-v2", "enabled")),
                                List.of(
                                        new TargetingRule(
                                                "late-rule",
                                                20,
                                                List.of(),
                                                "enabled"),
                                        new TargetingRule(
                                                "early-rule",
                                                10,
                                                List.of(),
                                                "enabled"))),
                        flag("checkout-v2", List.of(), List.of())),
                List.of());

        TargetingConfiguration validated = validator.validate(
                publishedFlags(),
                requested);

        assertThat(validated.flags())
                .extracting(FlagTarget::key)
                .containsExactly("checkout-v2", "payments-v2");
        assertThat(validated.flags().get(1).rules())
                .extracting(TargetingRule::key)
                .containsExactly("early-rule", "late-rule");
    }

    @Test
    void rejectsCyclicPrerequisiteGraphsWithAStableCode() {
        TargetingConfiguration requested = new TargetingConfiguration(
                List.of(
                        flag(
                                "checkout-v2",
                                List.of(new Prerequisite("payments-v2", "enabled")),
                                List.of()),
                        flag(
                                "payments-v2",
                                List.of(new Prerequisite("checkout-v2", "enabled")),
                                List.of())),
                List.of());

        PublicationGraphException failure = assertThrows(
                PublicationGraphException.class,
                () -> validator.validate(publishedFlags(), requested));

        assertThat(failure.validationCode()).isEqualTo("CYCLIC_PREREQUISITE");
    }

    @Test
    void rejectsUnknownSegmentReferencesWithAStableCode() {
        TargetingConfiguration requested = new TargetingConfiguration(
                List.of(
                        flag(
                                "checkout-v2",
                                List.of(),
                                List.of(new TargetingRule(
                                        "internal-users",
                                        10,
                                        List.of(new SegmentCondition(
                                                "missing-segment",
                                                false)),
                                        "enabled"))),
                        flag("payments-v2", List.of(), List.of())),
                List.of());

        PublicationGraphException failure = assertThrows(
                PublicationGraphException.class,
                () -> validator.validate(publishedFlags(), requested));

        assertThat(failure.validationCode()).isEqualTo("UNKNOWN_SEGMENT");
    }

    @Test
    void rejectsFlagsOutsideTheAuthenticatedEnvironmentCandidate() {
        TargetingConfiguration requested = new TargetingConfiguration(
                List.of(
                        flag("checkout-v2", List.of(), List.of()),
                        flag("external-tenant-flag", List.of(), List.of())),
                List.of());

        PublicationGraphException failure = assertThrows(
                PublicationGraphException.class,
                () -> validator.validate(publishedFlags(), requested));

        assertThat(failure.validationCode()).isEqualTo("FLAG_SCOPE_MISMATCH");
    }

    @Test
    void rejectsVariantSetsThatDoNotMatchThePublishedFlag() {
        FlagTarget incompatible = new FlagTarget(
                "checkout-v2",
                Set.of("disabled", "enabled", "experimental"),
                "disabled",
                List.of(),
                List.of());
        TargetingConfiguration requested = new TargetingConfiguration(
                List.of(incompatible, flag("payments-v2", List.of(), List.of())),
                List.of());

        PublicationGraphException failure = assertThrows(
                PublicationGraphException.class,
                () -> validator.validate(publishedFlags(), requested));

        assertThat(failure.validationCode())
                .isEqualTo("VARIANT_SCOPE_MISMATCH");
    }

    private static List<PublishedFlag> publishedFlags() {
        return List.of(
                publishedFlag("checkout-v2"),
                publishedFlag("payments-v2"));
    }

    private static PublishedFlag publishedFlag(String key) {
        return new PublishedFlag(
                key,
                PublishedValueType.BOOLEAN,
                true,
                "disabled",
                List.of(
                        PublishedVariant.booleanValue("disabled", false),
                        PublishedVariant.booleanValue("enabled", true)));
    }

    private static FlagTarget flag(
            String key,
            List<Prerequisite> prerequisites,
            List<TargetingRule> rules) {
        return new FlagTarget(
                key,
                Set.of("disabled", "enabled"),
                "disabled",
                prerequisites,
                rules);
    }
}
