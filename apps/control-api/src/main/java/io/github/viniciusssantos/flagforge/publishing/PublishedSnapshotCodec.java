package io.github.viniciusssantos.flagforge.publishing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public final class PublishedSnapshotCodec {

    public static final int SCHEMA_VERSION = 1;
    public static final String ALGORITHM_VERSION = "flagforge-evaluation-v1";
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;
    public static final int MAX_FLAGS = 1_000;
    public static final int MAX_VARIANTS_PER_FLAG = 100;

    private static final byte[] MAGIC = "FFSNAP01".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_KEY_BYTES = 63;
    private static final int MAX_ALGORITHM_BYTES = 64;
    private static final int MAX_STRING_VALUE_BYTES = 2_048;
    private static final Pattern KEY_PATTERN =
            Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    public EncodedSnapshot encode(PublishedSnapshot snapshot) {
        validate(snapshot);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.write(MAGIC);
                output.writeInt(snapshot.schemaVersion());
                writeUuid(output, snapshot.organizationId());
                writeUuid(output, snapshot.projectId());
                writeUuid(output, snapshot.environmentId());
                output.writeLong(snapshot.revisionNumber());
                writeString(output, snapshot.algorithmVersion(), MAX_ALGORITHM_BYTES);
                output.writeInt(snapshot.flags().size());
                for (PublishedFlag flag : snapshot.flags()) {
                    writeFlag(output, flag);
                }
            }
            byte[] payload = bytes.toByteArray();
            requirePayloadBound(payload);
            return new EncodedSnapshot(snapshot, payload, checksum(payload));
        } catch (IOException exception) {
            throw new SnapshotCodecException(
                    CodecError.INVALID_PAYLOAD,
                    "Unable to serialize configuration snapshot",
                    exception);
        }
    }

    public PublishedSnapshot decode(byte[] payload, String expectedChecksum) {
        Objects.requireNonNull(payload, "snapshot payload is required");
        Objects.requireNonNull(expectedChecksum, "expected checksum is required");
        requirePayloadBound(payload);
        String actualChecksum = checksum(payload);
        if (!MessageDigest.isEqual(
                actualChecksum.getBytes(StandardCharsets.US_ASCII),
                expectedChecksum.getBytes(StandardCharsets.US_ASCII))) {
            throw new SnapshotCodecException(
                    CodecError.CHECKSUM_MISMATCH,
                    "Published snapshot checksum does not match its payload");
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] magic = input.readNBytes(MAGIC.length);
            if (!MessageDigest.isEqual(magic, MAGIC)) {
                throw invalidPayload("Published snapshot magic header is invalid");
            }
            int schemaVersion = input.readInt();
            if (schemaVersion != SCHEMA_VERSION) {
                throw new SnapshotCodecException(
                        CodecError.UNSUPPORTED_SCHEMA,
                        "Published snapshot schema version is not supported");
            }
            UUID organizationId = readUuid(input);
            UUID projectId = readUuid(input);
            UUID environmentId = readUuid(input);
            long revisionNumber = input.readLong();
            String algorithmVersion = readString(input, MAX_ALGORITHM_BYTES);
            int flagCount = readCount(input, MAX_FLAGS, "flag");
            List<PublishedFlag> flags = new ArrayList<>(flagCount);
            for (int index = 0; index < flagCount; index++) {
                flags.add(readFlag(input));
            }
            if (input.available() != 0) {
                throw invalidPayload("Published snapshot has trailing bytes");
            }
            PublishedSnapshot snapshot = new PublishedSnapshot(
                    schemaVersion,
                    organizationId,
                    projectId,
                    environmentId,
                    revisionNumber,
                    algorithmVersion,
                    flags);
            validate(snapshot);
            return snapshot;
        } catch (EOFException exception) {
            throw new SnapshotCodecException(
                    CodecError.INVALID_PAYLOAD,
                    "Published snapshot ended before all fields were read",
                    exception);
        } catch (IOException exception) {
            throw new SnapshotCodecException(
                    CodecError.INVALID_PAYLOAD,
                    "Unable to deserialize configuration snapshot",
                    exception);
        }
    }

    public String checksum(byte[] payload) {
        Objects.requireNonNull(payload, "snapshot payload is required");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static void writeFlag(DataOutputStream output, PublishedFlag flag)
            throws IOException {
        writeString(output, flag.key(), MAX_KEY_BYTES);
        output.writeByte(flag.valueType().wireValue());
        output.writeBoolean(flag.enabled());
        writeString(output, flag.defaultVariant(), MAX_KEY_BYTES);
        output.writeInt(flag.variants().size());
        for (PublishedVariant variant : flag.variants()) {
            writeString(output, variant.key(), MAX_KEY_BYTES);
            output.writeByte(variant.valueType().wireValue());
            switch (variant.valueType()) {
                case BOOLEAN -> output.writeBoolean(variant.booleanValue());
                case STRING -> writeString(
                        output,
                        variant.stringValue(),
                        MAX_STRING_VALUE_BYTES);
            }
        }
    }

    private static PublishedFlag readFlag(DataInputStream input) throws IOException {
        String key = readString(input, MAX_KEY_BYTES);
        PublishedValueType valueType = PublishedValueType.fromWire(input.readByte());
        boolean enabled = input.readBoolean();
        String defaultVariant = readString(input, MAX_KEY_BYTES);
        int variantCount = readCount(input, MAX_VARIANTS_PER_FLAG, "variant");
        List<PublishedVariant> variants = new ArrayList<>(variantCount);
        for (int index = 0; index < variantCount; index++) {
            String variantKey = readString(input, MAX_KEY_BYTES);
            PublishedValueType variantType = PublishedValueType.fromWire(input.readByte());
            PublishedVariant variant = switch (variantType) {
                case BOOLEAN -> PublishedVariant.booleanValue(
                        variantKey,
                        input.readBoolean());
                case STRING -> PublishedVariant.stringValue(
                        variantKey,
                        readString(input, MAX_STRING_VALUE_BYTES));
            };
            variants.add(variant);
        }
        return new PublishedFlag(
                key,
                valueType,
                enabled,
                defaultVariant,
                variants);
    }

    private static void writeUuid(DataOutputStream output, UUID value)
            throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeString(
            DataOutputStream output,
            String value,
            int maximumBytes) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumBytes) {
            throw new SnapshotCodecException(
                    CodecError.INVALID_CONFIGURATION,
                    "Snapshot string exceeds its UTF-8 byte limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maximumBytes)
            throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximumBytes) {
            throw invalidPayload("Published snapshot string length is invalid");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("snapshot string is truncated");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readCount(
            DataInputStream input,
            int maximum,
            String elementName) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) {
            throw invalidPayload("Published snapshot " + elementName + " count is invalid");
        }
        return count;
    }

    private static void validate(PublishedSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "published snapshot is required");
        if (snapshot.schemaVersion() != SCHEMA_VERSION) {
            throw new SnapshotCodecException(
                    CodecError.UNSUPPORTED_SCHEMA,
                    "Published snapshot schema version is not supported");
        }
        Objects.requireNonNull(snapshot.organizationId(), "organization id is required");
        Objects.requireNonNull(snapshot.projectId(), "project id is required");
        Objects.requireNonNull(snapshot.environmentId(), "environment id is required");
        if (snapshot.revisionNumber() <= 0) {
            throw invalidConfiguration("Revision number must be positive");
        }
        if (!ALGORITHM_VERSION.equals(snapshot.algorithmVersion())) {
            throw invalidConfiguration("Snapshot algorithm version is not supported");
        }
        if (snapshot.flags().size() > MAX_FLAGS) {
            throw invalidConfiguration("Snapshot exceeds the maximum flag count");
        }

        Set<String> flagKeys = new HashSet<>();
        String previousFlagKey = null;
        for (PublishedFlag flag : snapshot.flags()) {
            validateFlag(flag);
            if (!flagKeys.add(flag.key())) {
                throw invalidConfiguration("Snapshot flag keys must be unique");
            }
            if (previousFlagKey != null && previousFlagKey.compareTo(flag.key()) >= 0) {
                throw invalidConfiguration("Snapshot flags must be sorted by key");
            }
            previousFlagKey = flag.key();
        }
    }

    private static void validateFlag(PublishedFlag flag) {
        Objects.requireNonNull(flag, "published flag is required");
        requireKey(flag.key(), "flag key");
        Objects.requireNonNull(flag.valueType(), "flag value type is required");
        requireKey(flag.defaultVariant(), "default variant");
        if (flag.variants().isEmpty()) {
            throw invalidConfiguration("Published flags require at least one variant");
        }
        if (flag.variants().size() > MAX_VARIANTS_PER_FLAG) {
            throw invalidConfiguration("Published flag exceeds the maximum variant count");
        }

        Set<String> variantKeys = new HashSet<>();
        String previousVariantKey = null;
        boolean defaultFound = false;
        for (PublishedVariant variant : flag.variants()) {
            validateVariant(flag.valueType(), variant);
            if (!variantKeys.add(variant.key())) {
                throw invalidConfiguration("Published variant keys must be unique");
            }
            if (previousVariantKey != null
                    && previousVariantKey.compareTo(variant.key()) >= 0) {
                throw invalidConfiguration("Published variants must be sorted by key");
            }
            previousVariantKey = variant.key();
            defaultFound |= flag.defaultVariant().equals(variant.key());
        }
        if (!defaultFound) {
            throw invalidConfiguration("Default variant is not declared by its flag");
        }
    }

    private static void validateVariant(
            PublishedValueType flagType,
            PublishedVariant variant) {
        Objects.requireNonNull(variant, "published variant is required");
        requireKey(variant.key(), "variant key");
        if (variant.valueType() != flagType) {
            throw invalidConfiguration("Variant type does not match its flag type");
        }
        switch (variant.valueType()) {
            case BOOLEAN -> {
                if (variant.booleanValue() == null || variant.stringValue() != null) {
                    throw invalidConfiguration("Boolean variant payload is invalid");
                }
            }
            case STRING -> {
                if (variant.booleanValue() != null || variant.stringValue() == null) {
                    throw invalidConfiguration("String variant payload is invalid");
                }
                int bytes = variant.stringValue().getBytes(StandardCharsets.UTF_8).length;
                if (bytes == 0 || bytes > MAX_STRING_VALUE_BYTES) {
                    throw invalidConfiguration("String variant payload is outside its bounds");
                }
            }
        }
    }

    private static void requireKey(String value, String description) {
        if (value == null || !KEY_PATTERN.matcher(value).matches()) {
            throw invalidConfiguration(description + " has an invalid format");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
            throw invalidConfiguration(description + " exceeds its UTF-8 byte limit");
        }
    }

    private static void requirePayloadBound(byte[] payload) {
        if (payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new SnapshotCodecException(
                    CodecError.PAYLOAD_TOO_LARGE,
                    "Published snapshot payload is empty or exceeds 1 MiB");
        }
    }

    private static SnapshotCodecException invalidConfiguration(String message) {
        return new SnapshotCodecException(CodecError.INVALID_CONFIGURATION, message);
    }

    private static SnapshotCodecException invalidPayload(String message) {
        return new SnapshotCodecException(CodecError.INVALID_PAYLOAD, message);
    }

    public enum PublishedValueType {
        BOOLEAN(1),
        STRING(2);

        private final int wireValue;

        PublishedValueType(int wireValue) {
            this.wireValue = wireValue;
        }

        int wireValue() {
            return wireValue;
        }

        static PublishedValueType fromWire(int wireValue) {
            return switch (wireValue) {
                case 1 -> BOOLEAN;
                case 2 -> STRING;
                default -> throw invalidPayload("Published value type is invalid");
            };
        }
    }

    public record PublishedVariant(
            String key,
            PublishedValueType valueType,
            Boolean booleanValue,
            String stringValue) {

        public static PublishedVariant booleanValue(String key, boolean value) {
            return new PublishedVariant(key, PublishedValueType.BOOLEAN, value, null);
        }

        public static PublishedVariant stringValue(String key, String value) {
            return new PublishedVariant(key, PublishedValueType.STRING, null, value);
        }
    }

    public record PublishedFlag(
            String key,
            PublishedValueType valueType,
            boolean enabled,
            String defaultVariant,
            List<PublishedVariant> variants) {

        public PublishedFlag {
            variants = List.copyOf(Objects.requireNonNull(variants, "variants are required"));
        }
    }

    public record PublishedSnapshot(
            int schemaVersion,
            UUID organizationId,
            UUID projectId,
            UUID environmentId,
            long revisionNumber,
            String algorithmVersion,
            List<PublishedFlag> flags) {

        public PublishedSnapshot {
            flags = List.copyOf(Objects.requireNonNull(flags, "flags are required"));
        }
    }

    public record EncodedSnapshot(
            PublishedSnapshot snapshot,
            byte[] payload,
            String checksum) {

        public EncodedSnapshot {
            Objects.requireNonNull(snapshot, "snapshot is required");
            payload = Objects.requireNonNull(payload, "payload is required").clone();
            Objects.requireNonNull(checksum, "checksum is required");
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    public enum CodecError {
        INVALID_CONFIGURATION,
        INVALID_PAYLOAD,
        PAYLOAD_TOO_LARGE,
        UNSUPPORTED_SCHEMA,
        CHECKSUM_MISMATCH
    }

    public static final class SnapshotCodecException extends RuntimeException {

        private final CodecError code;

        public SnapshotCodecException(CodecError code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code is required");
        }

        public SnapshotCodecException(
                CodecError code,
                String message,
                Throwable cause) {
            super(message, cause);
            this.code = Objects.requireNonNull(code, "code is required");
        }

        public CodecError code() {
            return code;
        }
    }
}
