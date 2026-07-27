package io.github.viniciusssantos.flagforge.publishing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.CodecError;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.EncodedSnapshot;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedFlag;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedSnapshot;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedValueType;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedVariant;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.SnapshotCodecException;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.EqualityCondition;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.FlagTarget;
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
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.VersionOperator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublishedSnapshotCodecTests {

    private static final String COMPATIBILITY_CHECKSUM =
            "9e33d4f246e4ed873a0f9aae0a824e97b4555d2eb24353f66ca121d74797ba50";

    private final PublishedSnapshotCodec codec = new PublishedSnapshotCodec();

    @Test
    void preservesTheVersionOneCompatibilityVector() {
        PublishedSnapshot snapshot = compatibilitySnapshot();

        EncodedSnapshot first = codec.encode(snapshot);
        EncodedSnapshot second = codec.encode(snapshot);
        PublishedSnapshot decoded = codec.decode(
                first.payload(),
                first.checksum());

        assertThat(first.payload()).hasSize(244);
        assertThat(first.checksum()).isEqualTo(COMPATIBILITY_CHECKSUM);
        assertThat(second.payload()).isEqualTo(first.payload());
        assertThat(second.checksum()).isEqualTo(first.checksum());
        assertThat(decoded).isEqualTo(snapshot);
        assertThat(decoded.targetingConfiguration().segments()).isEmpty();
        assertThat(decoded.targetingConfiguration().flags())
                .allSatisfy(flag -> {
                    assertThat(flag.prerequisites()).isEmpty();
                    assertThat(flag.rules()).isEmpty();
                });
    }

    @Test
    void roundTripsTheCompleteVersionTwoTargetingGraphDeterministically() {
        PublishedSnapshot snapshot = graphSnapshot();

        EncodedSnapshot first = codec.encode(snapshot);
        EncodedSnapshot second = codec.encode(snapshot);
        PublishedSnapshot decoded = codec.decode(
                first.payload(),
                first.checksum());

        assertThat(first.payload()).hasSizeGreaterThan(244);
        assertThat(second.payload()).isEqualTo(first.payload());
        assertThat(second.checksum()).isEqualTo(first.checksum());
        assertThat(decoded).isEqualTo(snapshot);
        assertThat(decoded.schemaVersion())
                .isEqualTo(PublishedSnapshotCodec.SCHEMA_VERSION);
        assertThat(decoded.targetingConfiguration().segments())
                .extracting(Segment::key)
                .containsExactly("staff");
        assertThat(decoded.targetingConfiguration().flags().get(1).prerequisites())
                .containsExactly(new Prerequisite("checkout-layout", "control"));
        assertThat(decoded.targetingConfiguration().flags().get(1).rules())
                .extracting(TargetingRule::key)
                .containsExactly("internal-users");
    }

    @Test
    void rejectsTamperingAndUnsupportedSchemaVersions() {
        EncodedSnapshot encoded = codec.encode(compatibilitySnapshot());
        byte[] tampered = encoded.payload();
        tampered[tampered.length - 1] ^= 1;

        SnapshotCodecException checksumFailure = assertThrows(
                SnapshotCodecException.class,
                () -> codec.decode(tampered, encoded.checksum()));
        assertThat(checksumFailure.code()).isEqualTo(CodecError.CHECKSUM_MISMATCH);

        byte[] unsupportedSchema = encoded.payload();
        unsupportedSchema[11] = 3;
        SnapshotCodecException schemaFailure = assertThrows(
                SnapshotCodecException.class,
                () -> codec.decode(
                        unsupportedSchema,
                        codec.checksum(unsupportedSchema)));
        assertThat(schemaFailure.code()).isEqualTo(CodecError.UNSUPPORTED_SCHEMA);
    }

    @Test
    void rejectsUnsortedOrUnboundedCandidateConfiguration() {
        PublishedFlag checkoutV2 = compatibilitySnapshot().flags().get(1);
        PublishedFlag checkoutLayout = compatibilitySnapshot().flags().get(0);
        PublishedSnapshot unsorted = new PublishedSnapshot(
                PublishedSnapshotCodec.SCHEMA_VERSION,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                7,
                PublishedSnapshotCodec.ALGORITHM_VERSION,
                List.of(checkoutV2, checkoutLayout));

        SnapshotCodecException orderingFailure = assertThrows(
                SnapshotCodecException.class,
                () -> codec.encode(unsorted));
        assertThat(orderingFailure.code()).isEqualTo(
                CodecError.INVALID_CONFIGURATION);

        PublishedFlag oversized = new PublishedFlag(
                "oversized-value",
                PublishedValueType.STRING,
                true,
                "control",
                List.of(PublishedVariant.stringValue(
                        "control",
                        "x".repeat(2_049))));
        PublishedSnapshot oversizedSnapshot = new PublishedSnapshot(
                PublishedSnapshotCodec.SCHEMA_VERSION,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                PublishedSnapshotCodec.ALGORITHM_VERSION,
                List.of(oversized));

        SnapshotCodecException boundFailure = assertThrows(
                SnapshotCodecException.class,
                () -> codec.encode(oversizedSnapshot));
        assertThat(boundFailure.code()).isEqualTo(
                CodecError.INVALID_CONFIGURATION);
    }

    @Test
    void rejectsPayloadsLargerThanThePublishedLimit() {
        byte[] oversized = new byte[PublishedSnapshotCodec.MAX_PAYLOAD_BYTES + 1];

        SnapshotCodecException failure = assertThrows(
                SnapshotCodecException.class,
                () -> codec.decode(oversized, codec.checksum(oversized)));

        assertThat(failure.code()).isEqualTo(CodecError.PAYLOAD_TOO_LARGE);
    }

    private static PublishedSnapshot compatibilitySnapshot() {
        List<PublishedFlag> flags = publishedFlags();
        return new PublishedSnapshot(
                PublishedSnapshotCodec.LEGACY_SCHEMA_VERSION,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                7,
                PublishedSnapshotCodec.ALGORITHM_VERSION,
                flags);
    }

    private static PublishedSnapshot graphSnapshot() {
        List<PublishedFlag> flags = publishedFlags();
        Segment staff = new Segment(
                "staff",
                Set.of("user-1"),
                Set.of("user-2"),
                List.of(
                        new EqualityCondition(
                                "country",
                                new StringValue("BR")),
                        new NumericCondition(
                                "age",
                                NumericOperator.GREATER_THAN_OR_EQUAL,
                                new BigDecimal("18")),
                        new SemanticVersionCondition(
                                "app-version",
                                VersionOperator.GREATER_THAN_OR_EQUAL,
                                "2.1.0"),
                        new StringSetCondition(
                                "plan",
                                Set.of("enterprise", "premium"))));
        TargetingConfiguration graph = new TargetingConfiguration(
                List.of(
                        new FlagTarget(
                                "checkout-layout",
                                Set.of("compact", "control"),
                                "control",
                                List.of(),
                                List.of()),
                        new FlagTarget(
                                "checkout-v2",
                                Set.of("disabled", "enabled"),
                                "disabled",
                                List.of(new Prerequisite(
                                        "checkout-layout",
                                        "control")),
                                List.of(new TargetingRule(
                                        "internal-users",
                                        10,
                                        List.of(new SegmentCondition(
                                                "staff",
                                                false)),
                                        "enabled")))),
                List.of(staff));
        return new PublishedSnapshot(
                PublishedSnapshotCodec.SCHEMA_VERSION,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                8,
                PublishedSnapshotCodec.ALGORITHM_VERSION,
                flags,
                graph);
    }

    private static List<PublishedFlag> publishedFlags() {
        PublishedFlag checkoutLayout = new PublishedFlag(
                "checkout-layout",
                PublishedValueType.STRING,
                true,
                "control",
                List.of(
                        PublishedVariant.stringValue("compact", "compact-v2"),
                        PublishedVariant.stringValue("control", "classic")));
        PublishedFlag checkoutV2 = new PublishedFlag(
                "checkout-v2",
                PublishedValueType.BOOLEAN,
                true,
                "disabled",
                List.of(
                        PublishedVariant.booleanValue("disabled", false),
                        PublishedVariant.booleanValue("enabled", true)));
        return List.of(checkoutLayout, checkoutV2);
    }
}
