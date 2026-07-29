package io.github.viniciusssantos.flagforge.publishing;

import java.util.List;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.CodecError;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.EncodedSnapshot;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedFlag;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedSnapshot;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedValueType;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedVariant;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.SnapshotCodecException;

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
        unsupportedSchema[11] = 2;
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
        return new PublishedSnapshot(
                PublishedSnapshotCodec.SCHEMA_VERSION,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                7,
                PublishedSnapshotCodec.ALGORITHM_VERSION,
                List.of(checkoutLayout, checkoutV2));
    }
}
