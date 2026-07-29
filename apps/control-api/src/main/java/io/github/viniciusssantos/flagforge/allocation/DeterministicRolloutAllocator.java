package io.github.viniciusssantos.flagforge.allocation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class DeterministicRolloutAllocator {

    public static final int BUCKET_COUNT = 100_000;
    public static final int MAX_TARGETING_KEY_BYTES = 1_024;

    private static final long UNSIGNED_INT_RANGE = 1L << Integer.SIZE;
    private static final byte[] MAGIC = {'F', 'F', 'R', 'A'};
    private static final Pattern KEY_PATTERN =
            Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private DeterministicRolloutAllocator() {
    }

    public static Allocation allocate(RolloutInput input) {
        RolloutInput normalized = normalize(Objects.requireNonNull(
                input,
                "input is required"));
        byte[] canonicalPayload = canonicalPayload(normalized);
        byte[] digest = sha256(canonicalPayload);
        long unsignedPrefix = ((digest[0] & 0xffL) << 24)
                | ((digest[1] & 0xffL) << 16)
                | ((digest[2] & 0xffL) << 8)
                | (digest[3] & 0xffL);
        int bucket = (int) ((unsignedPrefix * BUCKET_COUNT) / UNSIGNED_INT_RANGE);

        return new Allocation(
                normalized.algorithmVersion(),
                bucket,
                HexFormat.of().formatHex(digest));
    }

    public static boolean isIncluded(
            RolloutInput input,
            RolloutPercentage rollout) {
        Objects.requireNonNull(rollout, "rollout is required");
        return rollout.includes(allocate(input).bucket());
    }

    static byte[] canonicalPayload(RolloutInput input) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.write(MAGIC);
                output.writeByte(input.algorithmVersion().wireVersion());
                writeField(output, input.organizationId().toString());
                writeField(output, input.projectId().toString());
                writeField(output, input.environmentId().toString());
                writeField(output, input.flagKey());
                writeField(output, input.allocationKey());
                writeField(output, input.targetingKey());
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to construct rollout hash input",
                    exception);
        }
    }

    static String canonicalPayloadHex(RolloutInput input) {
        return HexFormat.of().formatHex(canonicalPayload(normalize(input)));
    }

    private static RolloutInput normalize(RolloutInput input) {
        AlgorithmVersion version = Objects.requireNonNull(
                input.algorithmVersion(),
                "algorithmVersion is required");
        UUID organizationId = Objects.requireNonNull(
                input.organizationId(),
                "organizationId is required");
        UUID projectId = Objects.requireNonNull(
                input.projectId(),
                "projectId is required");
        UUID environmentId = Objects.requireNonNull(
                input.environmentId(),
                "environmentId is required");
        String flagKey = normalizeKey(input.flagKey(), "flagKey");
        String allocationKey = normalizeKey(
                input.allocationKey(),
                "allocationKey");
        String targetingKey = normalizeTargetingKey(input.targetingKey());

        return new RolloutInput(
                version,
                organizationId,
                projectId,
                environmentId,
                flagKey,
                allocationKey,
                targetingKey);
    }

    private static String normalizeKey(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " is required");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!KEY_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    fieldName
                            + " must use lowercase letters, digits, and internal hyphens");
        }
        return normalized;
    }

    private static String normalizeTargetingKey(String value) {
        Objects.requireNonNull(value, "targetingKey is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException("targetingKey cannot be blank");
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        int byteLength = normalized.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > MAX_TARGETING_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "targetingKey cannot exceed "
                            + MAX_TARGETING_KEY_BYTES
                            + " UTF-8 bytes");
        }
        return normalized;
    }

    private static void writeField(DataOutputStream output, String value)
            throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public enum AlgorithmVersion {
        V1("flagforge-rollout-v1", 1);

        private final String id;
        private final int wireVersion;

        AlgorithmVersion(String id, int wireVersion) {
            this.id = id;
            this.wireVersion = wireVersion;
        }

        public String id() {
            return id;
        }

        public int wireVersion() {
            return wireVersion;
        }
    }

    public record RolloutInput(
            AlgorithmVersion algorithmVersion,
            UUID organizationId,
            UUID projectId,
            UUID environmentId,
            String flagKey,
            String allocationKey,
            String targetingKey) {
    }

    public record Allocation(
            AlgorithmVersion algorithmVersion,
            int bucket,
            String sha256) {

        public Allocation {
            Objects.requireNonNull(
                    algorithmVersion,
                    "algorithmVersion is required");
            if (bucket < 0 || bucket >= BUCKET_COUNT) {
                throw new IllegalArgumentException(
                        "bucket must be between 0 and " + (BUCKET_COUNT - 1));
            }
            Objects.requireNonNull(sha256, "sha256 is required");
        }
    }

    public record RolloutPercentage(int units) {

        public RolloutPercentage {
            if (units < 0 || units > BUCKET_COUNT) {
                throw new IllegalArgumentException(
                        "rollout units must be between 0 and " + BUCKET_COUNT);
            }
        }

        public static RolloutPercentage fromPercent(BigDecimal percent) {
            Objects.requireNonNull(percent, "percent is required");
            if (percent.signum() < 0
                    || percent.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException(
                        "percent must be between 0 and 100");
            }
            try {
                return new RolloutPercentage(
                        percent.movePointRight(3).intValueExact());
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                        "percent supports at most three decimal places",
                        exception);
            }
        }

        public BigDecimal percent() {
            return BigDecimal.valueOf(units, 3);
        }

        public boolean includes(int bucket) {
            if (bucket < 0 || bucket >= BUCKET_COUNT) {
                throw new IllegalArgumentException(
                        "bucket must be between 0 and " + (BUCKET_COUNT - 1));
            }
            return bucket < units;
        }
    }
}
