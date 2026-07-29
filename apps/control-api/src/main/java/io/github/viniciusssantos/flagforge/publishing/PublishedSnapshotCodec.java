package io.github.viniciusssantos.flagforge.publishing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.github.viniciusssantos.flagforge.targeting.TargetingEngine;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.AttributeValue;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.BooleanValue;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.Condition;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.EqualityCondition;
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

import org.springframework.stereotype.Component;

@Component
public final class PublishedSnapshotCodec {

    public static final int LEGACY_SCHEMA_VERSION = 1;
    public static final int SCHEMA_VERSION = 2;
    public static final String ALGORITHM_VERSION = "flagforge-evaluation-v1";
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;
    public static final int MAX_FLAGS = 1_000;
    public static final int MAX_VARIANTS_PER_FLAG = 100;

    private static final byte[] MAGIC = "FFSNAP01".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_KEY_BYTES = 63;
    private static final int MAX_GRAPH_KEY_BYTES = 127;
    private static final int MAX_ALGORITHM_BYTES = 64;
    private static final int MAX_STRING_VALUE_BYTES = 2_048;
    private static final int MAX_TARGETING_KEY_BYTES = 2_048;
    private static final int MAX_DECIMAL_BYTES = 256;
    private static final int MAX_SEMANTIC_VERSION_BYTES = 256;
    private static final int MAX_SET_VALUES = 4_096;
    private static final int MAX_TARGETING_KEYS_PER_SEGMENT = 10_000;
    private static final int MAX_PREREQUISITES_PER_FLAG = 1_000;
    private static final Pattern KEY_PATTERN =
            Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Comparator<Condition> CONDITION_ORDER = Comparator
            .comparing((Condition condition) -> condition.getClass().getName())
            .thenComparing(Object::toString);

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
                if (snapshot.schemaVersion() >= SCHEMA_VERSION) {
                    writeTargetingConfiguration(
                            output,
                            snapshot.targetingConfiguration());
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
            requireSupportedSchema(schemaVersion);
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
            TargetingConfiguration targetingConfiguration =
                    schemaVersion == LEGACY_SCHEMA_VERSION
                            ? defaultTargetingConfiguration(flags)
                            : readTargetingConfiguration(input, flags);
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
                    flags,
                    targetingConfiguration);
            validate(snapshot);
            return snapshot;
        } catch (SnapshotCodecException exception) {
            throw exception;
        } catch (EOFException exception) {
            throw new SnapshotCodecException(
                    CodecError.INVALID_PAYLOAD,
                    "Published snapshot ended before all fields were read",
                    exception);
        } catch (IOException | IllegalArgumentException exception) {
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
                default -> throw new IllegalStateException(
                        "Published variant type is unsupported");
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

    private static void writeTargetingConfiguration(
            DataOutputStream output,
            TargetingConfiguration configuration) throws IOException {
        List<FlagTarget> flags = configuration.flags().stream()
                .sorted(Comparator.comparing(FlagTarget::key))
                .toList();
        output.writeInt(flags.size());
        for (FlagTarget flag : flags) {
            writeString(output, flag.key(), MAX_GRAPH_KEY_BYTES);
            List<Prerequisite> prerequisites = flag.prerequisites().stream()
                    .sorted(Comparator
                            .comparing(Prerequisite::flagKey)
                            .thenComparing(Prerequisite::expectedVariant))
                    .toList();
            output.writeInt(prerequisites.size());
            for (Prerequisite prerequisite : prerequisites) {
                writeString(
                        output,
                        prerequisite.flagKey(),
                        MAX_GRAPH_KEY_BYTES);
                writeString(
                        output,
                        prerequisite.expectedVariant(),
                        MAX_GRAPH_KEY_BYTES);
            }
            List<TargetingRule> rules = flag.rules().stream()
                    .sorted(Comparator
                            .comparingInt(TargetingRule::priority)
                            .thenComparing(TargetingRule::key))
                    .toList();
            output.writeInt(rules.size());
            for (TargetingRule rule : rules) {
                writeRule(output, rule);
            }
        }

        List<Segment> segments = configuration.segments().stream()
                .sorted(Comparator.comparing(Segment::key))
                .toList();
        output.writeInt(segments.size());
        for (Segment segment : segments) {
            writeString(output, segment.key(), MAX_GRAPH_KEY_BYTES);
            writeSortedStrings(
                    output,
                    segment.includedTargetingKeys(),
                    MAX_TARGETING_KEY_BYTES);
            writeSortedStrings(
                    output,
                    segment.excludedTargetingKeys(),
                    MAX_TARGETING_KEY_BYTES);
            writeConditions(output, segment.conditions());
        }
    }

    private static TargetingConfiguration readTargetingConfiguration(
            DataInputStream input,
            List<PublishedFlag> publishedFlags) throws IOException {
        Map<String, PublishedFlag> publishedByKey = publishedFlags.stream()
                .collect(Collectors.toMap(
                        PublishedFlag::key,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
        int flagCount = readCount(input, MAX_FLAGS, "targeting flag");
        List<FlagTarget> flags = new ArrayList<>(flagCount);
        Set<String> seenFlags = new HashSet<>();
        for (int index = 0; index < flagCount; index++) {
            String key = readString(input, MAX_GRAPH_KEY_BYTES);
            if (!seenFlags.add(key)) {
                throw invalidPayload("Targeting graph contains duplicate flags");
            }
            PublishedFlag published = publishedByKey.get(key);
            if (published == null) {
                throw invalidPayload("Targeting graph references an unknown flag");
            }
            int prerequisiteCount = readCount(
                    input,
                    MAX_PREREQUISITES_PER_FLAG,
                    "prerequisite");
            List<Prerequisite> prerequisites = new ArrayList<>(prerequisiteCount);
            for (int prerequisiteIndex = 0;
                    prerequisiteIndex < prerequisiteCount;
                    prerequisiteIndex++) {
                prerequisites.add(new Prerequisite(
                        readString(input, MAX_GRAPH_KEY_BYTES),
                        readString(input, MAX_GRAPH_KEY_BYTES)));
            }
            int ruleCount = readCount(
                    input,
                    TargetingEngine.MAX_RULES_PER_FLAG,
                    "rule");
            List<TargetingRule> rules = new ArrayList<>(ruleCount);
            for (int ruleIndex = 0; ruleIndex < ruleCount; ruleIndex++) {
                rules.add(readRule(input));
            }
            flags.add(new FlagTarget(
                    key,
                    published.variants().stream()
                            .map(PublishedVariant::key)
                            .collect(Collectors.toUnmodifiableSet()),
                    published.defaultVariant(),
                    prerequisites,
                    rules));
        }

        int segmentCount = readCount(
                input,
                TargetingEngine.MAX_SEGMENTS,
                "segment");
        List<Segment> segments = new ArrayList<>(segmentCount);
        for (int index = 0; index < segmentCount; index++) {
            segments.add(new Segment(
                    readString(input, MAX_GRAPH_KEY_BYTES),
                    readStringSet(
                            input,
                            MAX_TARGETING_KEYS_PER_SEGMENT,
                            MAX_TARGETING_KEY_BYTES,
                            "included targeting key"),
                    readStringSet(
                            input,
                            MAX_TARGETING_KEYS_PER_SEGMENT,
                            MAX_TARGETING_KEY_BYTES,
                            "excluded targeting key"),
                    readConditions(input)));
        }
        return new TargetingConfiguration(flags, segments);
    }

    private static void writeRule(
            DataOutputStream output,
            TargetingRule rule) throws IOException {
        writeString(output, rule.key(), MAX_GRAPH_KEY_BYTES);
        output.writeInt(rule.priority());
        writeString(output, rule.variantKey(), MAX_GRAPH_KEY_BYTES);
        writeConditions(output, rule.conditions());
    }

    private static TargetingRule readRule(DataInputStream input) throws IOException {
        String key = readString(input, MAX_GRAPH_KEY_BYTES);
        int priority = input.readInt();
        String variantKey = readString(input, MAX_GRAPH_KEY_BYTES);
        return new TargetingRule(
                key,
                priority,
                readConditions(input),
                variantKey);
    }

    private static void writeConditions(
            DataOutputStream output,
            List<Condition> conditions) throws IOException {
        List<Condition> ordered = conditions.stream()
                .sorted(CONDITION_ORDER)
                .toList();
        output.writeInt(ordered.size());
        for (Condition condition : ordered) {
            writeCondition(output, condition);
        }
    }

    private static List<Condition> readConditions(DataInputStream input)
            throws IOException {
        int count = readCount(
                input,
                TargetingEngine.MAX_CONDITIONS,
                "condition");
        List<Condition> conditions = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            conditions.add(readCondition(input));
        }
        return conditions;
    }

    private static void writeCondition(
            DataOutputStream output,
            Condition condition) throws IOException {
        if (condition instanceof EqualityCondition equality) {
            output.writeByte(1);
            writeString(output, equality.attribute(), MAX_GRAPH_KEY_BYTES);
            writeAttributeValue(output, equality.expected());
            return;
        }
        if (condition instanceof StringSetCondition membership) {
            output.writeByte(2);
            writeString(output, membership.attribute(), MAX_GRAPH_KEY_BYTES);
            writeSortedStrings(output, membership.values(), MAX_STRING_VALUE_BYTES);
            return;
        }
        if (condition instanceof NumericCondition numeric) {
            output.writeByte(3);
            writeString(output, numeric.attribute(), MAX_GRAPH_KEY_BYTES);
            output.writeByte(numericOperatorWire(numeric.operator()));
            writeString(output, numeric.operand().toPlainString(), MAX_DECIMAL_BYTES);
            return;
        }
        if (condition instanceof SemanticVersionCondition version) {
            output.writeByte(4);
            writeString(output, version.attribute(), MAX_GRAPH_KEY_BYTES);
            output.writeByte(versionOperatorWire(version.operator()));
            writeString(output, version.operand(), MAX_SEMANTIC_VERSION_BYTES);
            return;
        }
        if (condition instanceof SegmentCondition segment) {
            output.writeByte(5);
            writeString(output, segment.segmentKey(), MAX_GRAPH_KEY_BYTES);
            output.writeBoolean(segment.negated());
            return;
        }
        throw invalidConfiguration("Published targeting condition is unsupported");
    }

    private static Condition readCondition(DataInputStream input) throws IOException {
        int type = input.readUnsignedByte();
        return switch (type) {
            case 1 -> new EqualityCondition(
                    readString(input, MAX_GRAPH_KEY_BYTES),
                    readAttributeValue(input));
            case 2 -> new StringSetCondition(
                    readString(input, MAX_GRAPH_KEY_BYTES),
                    readStringSet(
                            input,
                            MAX_SET_VALUES,
                            MAX_STRING_VALUE_BYTES,
                            "membership value"));
            case 3 -> new NumericCondition(
                    readString(input, MAX_GRAPH_KEY_BYTES),
                    numericOperatorFromWire(input.readUnsignedByte()),
                    new BigDecimal(readString(input, MAX_DECIMAL_BYTES)));
            case 4 -> new SemanticVersionCondition(
                    readString(input, MAX_GRAPH_KEY_BYTES),
                    versionOperatorFromWire(input.readUnsignedByte()),
                    readString(input, MAX_SEMANTIC_VERSION_BYTES));
            case 5 -> new SegmentCondition(
                    readString(input, MAX_GRAPH_KEY_BYTES),
                    input.readBoolean());
            default -> throw invalidPayload(
                    "Published targeting condition type is invalid");
        };
    }

    private static void writeAttributeValue(
            DataOutputStream output,
            AttributeValue value) throws IOException {
        if (value instanceof StringValue stringValue) {
            output.writeByte(1);
            writeString(output, stringValue.value(), MAX_STRING_VALUE_BYTES);
            return;
        }
        if (value instanceof NumberValue numberValue) {
            output.writeByte(2);
            writeString(
                    output,
                    numberValue.value().toPlainString(),
                    MAX_DECIMAL_BYTES);
            return;
        }
        if (value instanceof BooleanValue booleanValue) {
            output.writeByte(3);
            output.writeBoolean(booleanValue.value());
            return;
        }
        throw invalidConfiguration("Published targeting value is unsupported");
    }

    private static AttributeValue readAttributeValue(DataInputStream input)
            throws IOException {
        int type = input.readUnsignedByte();
        return switch (type) {
            case 1 -> new StringValue(readString(input, MAX_STRING_VALUE_BYTES));
            case 2 -> new NumberValue(new BigDecimal(
                    readString(input, MAX_DECIMAL_BYTES)));
            case 3 -> new BooleanValue(input.readBoolean());
            default -> throw invalidPayload(
                    "Published targeting value type is invalid");
        };
    }

    private static void writeSortedStrings(
            DataOutputStream output,
            Set<String> values,
            int maximumBytes) throws IOException {
        List<String> sorted = values.stream().sorted().toList();
        output.writeInt(sorted.size());
        for (String value : sorted) {
            writeString(output, value, maximumBytes);
        }
    }

    private static Set<String> readStringSet(
            DataInputStream input,
            int maximumCount,
            int maximumBytes,
            String elementName) throws IOException {
        int count = readCount(input, maximumCount, elementName);
        Set<String> values = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            String value = readString(input, maximumBytes);
            if (!values.add(value)) {
                throw invalidPayload(
                        "Published snapshot contains duplicate " + elementName);
            }
        }
        return Set.copyOf(values);
    }

    private static int numericOperatorWire(NumericOperator operator) {
        return switch (operator) {
            case EQUAL -> 1;
            case LESS_THAN -> 2;
            case LESS_THAN_OR_EQUAL -> 3;
            case GREATER_THAN -> 4;
            case GREATER_THAN_OR_EQUAL -> 5;
            default -> throw new IllegalStateException(
                    "Numeric operator is unsupported");
        };
    }

    private static NumericOperator numericOperatorFromWire(int wireValue) {
        return switch (wireValue) {
            case 1 -> NumericOperator.EQUAL;
            case 2 -> NumericOperator.LESS_THAN;
            case 3 -> NumericOperator.LESS_THAN_OR_EQUAL;
            case 4 -> NumericOperator.GREATER_THAN;
            case 5 -> NumericOperator.GREATER_THAN_OR_EQUAL;
            default -> throw invalidPayload("Numeric operator is invalid");
        };
    }

    private static int versionOperatorWire(VersionOperator operator) {
        return switch (operator) {
            case EQUAL -> 1;
            case LESS_THAN -> 2;
            case LESS_THAN_OR_EQUAL -> 3;
            case GREATER_THAN -> 4;
            case GREATER_THAN_OR_EQUAL -> 5;
            default -> throw new IllegalStateException(
                    "Semantic version operator is unsupported");
        };
    }

    private static VersionOperator versionOperatorFromWire(int wireValue) {
        return switch (wireValue) {
            case 1 -> VersionOperator.EQUAL;
            case 2 -> VersionOperator.LESS_THAN;
            case 3 -> VersionOperator.LESS_THAN_OR_EQUAL;
            case 4 -> VersionOperator.GREATER_THAN;
            case 5 -> VersionOperator.GREATER_THAN_OR_EQUAL;
            default -> throw invalidPayload("Semantic version operator is invalid");
        };
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
        requireSupportedSchema(snapshot.schemaVersion());
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
        validateTargetingConfiguration(
                snapshot.schemaVersion(),
                snapshot.flags(),
                snapshot.targetingConfiguration());
    }

    private static void validateTargetingConfiguration(
            int schemaVersion,
            List<PublishedFlag> publishedFlags,
            TargetingConfiguration configuration) {
        Objects.requireNonNull(configuration, "targeting configuration is required");
        try {
            TargetingEngine.validate(configuration);
        } catch (TargetingValidationException exception) {
            throw invalidConfiguration(
                    "Published targeting graph is invalid: "
                            + exception.errorCode().name());
        }

        Map<String, PublishedFlag> publishedByKey = publishedFlags.stream()
                .collect(Collectors.toMap(
                        PublishedFlag::key,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
        if (configuration.flags().size() != publishedByKey.size()) {
            throw invalidConfiguration(
                    "Published targeting graph must contain every flag");
        }
        for (FlagTarget target : configuration.flags()) {
            PublishedFlag published = publishedByKey.get(target.key());
            if (published == null) {
                throw invalidConfiguration(
                        "Published targeting graph references an unknown flag");
            }
            Set<String> variants = published.variants().stream()
                    .map(PublishedVariant::key)
                    .collect(Collectors.toUnmodifiableSet());
            if (!variants.equals(target.variants())
                    || !published.defaultVariant().equals(target.defaultVariant())) {
                throw invalidConfiguration(
                        "Published targeting graph does not match flag variants");
            }
            if (schemaVersion == LEGACY_SCHEMA_VERSION
                    && (!target.prerequisites().isEmpty()
                    || !target.rules().isEmpty())) {
                throw invalidConfiguration(
                        "Snapshot schema v1 cannot contain targeting rules");
            }
        }
        if (schemaVersion == LEGACY_SCHEMA_VERSION
                && !configuration.segments().isEmpty()) {
            throw invalidConfiguration(
                    "Snapshot schema v1 cannot contain segments");
        }
    }

    private static TargetingConfiguration defaultTargetingConfiguration(
            List<PublishedFlag> flags) {
        List<FlagTarget> targets = flags.stream()
                .map(flag -> new FlagTarget(
                        flag.key(),
                        flag.variants().stream()
                                .map(PublishedVariant::key)
                                .collect(Collectors.toUnmodifiableSet()),
                        flag.defaultVariant(),
                        List.of(),
                        List.of()))
                .toList();
        return new TargetingConfiguration(targets, List.of());
    }

    private static void requireSupportedSchema(int schemaVersion) {
        if (schemaVersion != LEGACY_SCHEMA_VERSION
                && schemaVersion != SCHEMA_VERSION) {
            throw new SnapshotCodecException(
                    CodecError.UNSUPPORTED_SCHEMA,
                    "Published snapshot schema version is not supported");
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
            default -> throw new IllegalStateException(
                    "Published variant type is unsupported");
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
            List<PublishedFlag> flags,
            TargetingConfiguration targetingConfiguration) {

        public PublishedSnapshot {
            flags = List.copyOf(Objects.requireNonNull(flags, "flags are required"));
            Objects.requireNonNull(
                    targetingConfiguration,
                    "targetingConfiguration is required");
        }

        public PublishedSnapshot(
                int schemaVersion,
                UUID organizationId,
                UUID projectId,
                UUID environmentId,
                long revisionNumber,
                String algorithmVersion,
                List<PublishedFlag> flags) {
            this(
                    schemaVersion,
                    organizationId,
                    projectId,
                    environmentId,
                    revisionNumber,
                    algorithmVersion,
                    flags,
                    defaultTargetingConfiguration(flags));
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
