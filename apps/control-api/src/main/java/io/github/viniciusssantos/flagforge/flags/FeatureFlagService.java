package io.github.viniciusssantos.flagforge.flags;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import io.github.viniciusssantos.flagforge.tenancy.ControlPlanePermission;
import io.github.viniciusssantos.flagforge.tenancy.Project;
import io.github.viniciusssantos.flagforge.tenancy.TenantAccessException;
import io.github.viniciusssantos.flagforge.tenancy.TenantAuthorizationService;
import io.github.viniciusssantos.flagforge.tenancy.TenantHierarchyService;
import io.github.viniciusssantos.flagforge.tenancy.TenantIdentity;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeatureFlagService {

    private static final Pattern KEY_PATTERN =
            Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Set<ValueType> SUPPORTED_TYPES =
            Set.of(ValueType.BOOLEAN, ValueType.STRING);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TenantAuthorizationService authorizationService;
    private final TenantHierarchyService tenantHierarchyService;

    public FeatureFlagService(
            NamedParameterJdbcTemplate jdbcTemplate,
            TenantAuthorizationService authorizationService,
            TenantHierarchyService tenantHierarchyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
        this.tenantHierarchyService = tenantHierarchyService;
    }

    @Transactional
    public FeatureFlag create(CreateFlagCommand command) {
        Objects.requireNonNull(command, "command is required");
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.FLAG_WRITE);
        Project project = tenantHierarchyService.findProject(command.projectId());
        ensureSameOrganization(identity, project.organizationId());

        String key = requireKey(command.key(), "flagKey");
        String displayName = requireText(command.displayName(), "displayName", 120);
        String ownerId = requireText(command.ownerId(), "ownerId", 128);
        String description = optionalText(command.description(), "description", 500);
        ValueType valueType = Objects.requireNonNull(
                command.valueType(), "valueType is required");
        requireSupportedType(valueType);
        LifecycleType lifecycleType = Objects.requireNonNull(
                command.lifecycleType(), "lifecycleType is required");
        validateRemovalDate(lifecycleType, command.expectedRemovalDate());
        List<Variant> variants = normalizeVariants(valueType, command.variants());
        String defaultVariantKey = requireKey(
                command.defaultVariantKey(), "defaultVariantKey");
        ensureDefaultVariantExists(defaultVariantKey, variants);

        UUID flagId = UUID.randomUUID();
        Instant now = now();
        String insertFlag = """
                INSERT INTO flagforge.feature_flags (
                    id,
                    organization_id,
                    project_id,
                    flag_key,
                    display_name,
                    description,
                    owner_id,
                    value_type,
                    lifecycle_type,
                    expected_removal_date,
                    default_variant_key,
                    state,
                    archived_at,
                    created_at,
                    updated_at,
                    version
                ) VALUES (
                    :id,
                    :organizationId,
                    :projectId,
                    :flagKey,
                    :displayName,
                    :description,
                    :ownerId,
                    :valueType,
                    :lifecycleType,
                    :expectedRemovalDate,
                    :defaultVariantKey,
                    'ACTIVE',
                    NULL,
                    :createdAt,
                    :updatedAt,
                    0
                )
                """;

        try {
            jdbcTemplate.update(
                    insertFlag,
                    new MapSqlParameterSource()
                            .addValue("id", flagId)
                            .addValue("organizationId", identity.organizationId())
                            .addValue("projectId", project.id())
                            .addValue("flagKey", key)
                            .addValue("displayName", displayName)
                            .addValue("description", description)
                            .addValue("ownerId", ownerId)
                            .addValue("valueType", valueType.name())
                            .addValue("lifecycleType", lifecycleType.name())
                            .addValue(
                                    "expectedRemovalDate",
                                    command.expectedRemovalDate() == null
                                            ? null
                                            : Date.valueOf(command.expectedRemovalDate()))
                            .addValue("defaultVariantKey", defaultVariantKey)
                            .addValue("createdAt", Timestamp.from(now))
                            .addValue("updatedAt", Timestamp.from(now)));
            insertVariants(identity.organizationId(), project.id(), flagId, variants, now);
        } catch (DuplicateKeyException exception) {
            throw new FlagValidationException(
                    ValidationCode.FLAG_KEY_ALREADY_EXISTS,
                    "Feature flag key already exists in this project",
                    exception);
        }

        return new FeatureFlag(
                flagId,
                identity.organizationId(),
                project.id(),
                key,
                displayName,
                description,
                ownerId,
                valueType,
                lifecycleType,
                command.expectedRemovalDate(),
                defaultVariantKey,
                FlagState.ACTIVE,
                null,
                now,
                now,
                0,
                variants);
    }

    @Transactional(readOnly = true)
    public FeatureFlag find(UUID projectId, UUID flagId) {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.FLAG_READ);
        Project project = tenantHierarchyService.findProject(projectId);
        ensureSameOrganization(identity, project.organizationId());
        return findScoped(identity.organizationId(), project.id(), flagId)
                .orElseThrow(TenantAccessException::resourceNotFound);
    }

    @Transactional(readOnly = true)
    public FeatureFlag findActiveByKey(UUID projectId, String key) {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.FLAG_READ);
        Project project = tenantHierarchyService.findProject(projectId);
        ensureSameOrganization(identity, project.organizationId());
        String normalizedKey = requireKey(key, "flagKey");

        String sql = """
                SELECT id
                FROM flagforge.feature_flags
                WHERE organization_id = :organizationId
                  AND project_id = :projectId
                  AND flag_key = :flagKey
                  AND state = 'ACTIVE'
                """;
        Optional<UUID> flagId = jdbcTemplate.query(
                        sql,
                        Map.of(
                                "organizationId", identity.organizationId(),
                                "projectId", project.id(),
                                "flagKey", normalizedKey),
                        (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class))
                .stream()
                .findFirst();
        return flagId
                .flatMap(id -> findScoped(identity.organizationId(), project.id(), id))
                .orElseThrow(TenantAccessException::resourceNotFound);
    }

    @Transactional
    public FeatureFlag archive(UUID projectId, UUID flagId) {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.FLAG_WRITE);
        Project project = tenantHierarchyService.findProject(projectId);
        ensureSameOrganization(identity, project.organizationId());
        FeatureFlag current = findScoped(identity.organizationId(), project.id(), flagId)
                .orElseThrow(TenantAccessException::resourceNotFound);
        if (current.state() == FlagState.ARCHIVED) {
            return current;
        }

        Instant archivedAt = now();
        String sql = """
                UPDATE flagforge.feature_flags
                SET state = 'ARCHIVED',
                    archived_at = :archivedAt,
                    updated_at = :updatedAt,
                    version = version + 1
                WHERE organization_id = :organizationId
                  AND project_id = :projectId
                  AND id = :flagId
                  AND state = 'ACTIVE'
                  AND version = :version
                """;
        int updated = jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("archivedAt", Timestamp.from(archivedAt))
                        .addValue("updatedAt", Timestamp.from(archivedAt))
                        .addValue("organizationId", identity.organizationId())
                        .addValue("projectId", project.id())
                        .addValue("flagId", flagId)
                        .addValue("version", current.version()));
        if (updated != 1) {
            throw new FlagValidationException(
                    ValidationCode.CONCURRENT_MODIFICATION,
                    "Feature flag changed concurrently");
        }

        return new FeatureFlag(
                current.id(),
                current.organizationId(),
                current.projectId(),
                current.key(),
                current.displayName(),
                current.description(),
                current.ownerId(),
                current.valueType(),
                current.lifecycleType(),
                current.expectedRemovalDate(),
                current.defaultVariantKey(),
                FlagState.ARCHIVED,
                archivedAt,
                current.createdAt(),
                archivedAt,
                current.version() + 1,
                current.variants());
    }

    private Optional<FeatureFlag> findScoped(
            UUID organizationId,
            UUID projectId,
            UUID flagId) {
        String sql = """
                SELECT
                    id,
                    organization_id,
                    project_id,
                    flag_key,
                    display_name,
                    description,
                    owner_id,
                    value_type,
                    lifecycle_type,
                    expected_removal_date,
                    default_variant_key,
                    state,
                    archived_at,
                    created_at,
                    updated_at,
                    version
                FROM flagforge.feature_flags
                WHERE organization_id = :organizationId
                  AND project_id = :projectId
                  AND id = :flagId
                """;
        Optional<FeatureFlagRow> flag = jdbcTemplate.query(
                        sql,
                        Map.of(
                                "organizationId", organizationId,
                                "projectId", projectId,
                                "flagId", flagId),
                        (resultSet, rowNumber) -> mapFlagRow(resultSet))
                .stream()
                .findFirst();
        if (flag.isEmpty()) {
            return Optional.empty();
        }

        FeatureFlagRow row = flag.get();
        return Optional.of(new FeatureFlag(
                row.id(),
                row.organizationId(),
                row.projectId(),
                row.key(),
                row.displayName(),
                row.description(),
                row.ownerId(),
                row.valueType(),
                row.lifecycleType(),
                row.expectedRemovalDate(),
                row.defaultVariantKey(),
                row.state(),
                row.archivedAt(),
                row.createdAt(),
                row.updatedAt(),
                row.version(),
                loadVariants(organizationId, projectId, flagId)));
    }

    private void insertVariants(
            UUID organizationId,
            UUID projectId,
            UUID flagId,
            List<Variant> variants,
            Instant now) {
        String sql = """
                INSERT INTO flagforge.feature_flag_variants (
                    id,
                    organization_id,
                    project_id,
                    flag_id,
                    variant_key,
                    value_type,
                    boolean_value,
                    string_value,
                    number_value,
                    json_value,
                    created_at
                ) VALUES (
                    :id,
                    :organizationId,
                    :projectId,
                    :flagId,
                    :variantKey,
                    :valueType,
                    :booleanValue,
                    :stringValue,
                    NULL,
                    NULL,
                    :createdAt
                )
                """;
        for (Variant variant : variants) {
            jdbcTemplate.update(
                    sql,
                    new MapSqlParameterSource()
                            .addValue("id", UUID.randomUUID())
                            .addValue("organizationId", organizationId)
                            .addValue("projectId", projectId)
                            .addValue("flagId", flagId)
                            .addValue("variantKey", variant.key())
                            .addValue("valueType", variant.valueType().name())
                            .addValue(
                                    "booleanValue",
                                    variant instanceof BooleanVariant booleanVariant
                                            ? booleanVariant.value()
                                            : null)
                            .addValue(
                                    "stringValue",
                                    variant instanceof StringVariant stringVariant
                                            ? stringVariant.value()
                                            : null)
                            .addValue("createdAt", Timestamp.from(now)));
        }
    }

    private List<Variant> loadVariants(
            UUID organizationId,
            UUID projectId,
            UUID flagId) {
        String sql = """
                SELECT
                    variant_key,
                    value_type,
                    boolean_value,
                    string_value
                FROM flagforge.feature_flag_variants
                WHERE organization_id = :organizationId
                  AND project_id = :projectId
                  AND flag_id = :flagId
                ORDER BY variant_key
                """;
        return jdbcTemplate.query(
                sql,
                Map.of(
                        "organizationId", organizationId,
                        "projectId", projectId,
                        "flagId", flagId),
                (resultSet, rowNumber) -> mapVariant(resultSet));
    }

    private static FeatureFlagRow mapFlagRow(ResultSet resultSet) throws SQLException {
        Date removalDate = resultSet.getDate("expected_removal_date");
        Timestamp archivedAt = resultSet.getTimestamp("archived_at");
        return new FeatureFlagRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getString("flag_key"),
                resultSet.getString("display_name"),
                resultSet.getString("description"),
                resultSet.getString("owner_id"),
                ValueType.valueOf(resultSet.getString("value_type")),
                LifecycleType.valueOf(resultSet.getString("lifecycle_type")),
                removalDate == null ? null : removalDate.toLocalDate(),
                resultSet.getString("default_variant_key"),
                FlagState.valueOf(resultSet.getString("state")),
                archivedAt == null ? null : archivedAt.toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant(),
                resultSet.getLong("version"));
    }

    private static Variant mapVariant(ResultSet resultSet) throws SQLException {
        String key = resultSet.getString("variant_key");
        ValueType valueType = ValueType.valueOf(resultSet.getString("value_type"));
        return switch (valueType) {
            case BOOLEAN -> new BooleanVariant(key, resultSet.getBoolean("boolean_value"));
            case STRING -> new StringVariant(key, resultSet.getString("string_value"));
            case NUMBER, JSON -> throw new FlagValidationException(
                    ValidationCode.UNSUPPORTED_VALUE_TYPE,
                    "Stored feature flag type is not enabled yet");
        };
    }

    private static List<Variant> normalizeVariants(
            ValueType valueType,
            List<Variant> variants) {
        if (variants == null || variants.isEmpty()) {
            throw new FlagValidationException(
                    ValidationCode.VARIANTS_REQUIRED,
                    "At least one variant is required");
        }
        List<Variant> normalized = new ArrayList<>(variants.size());
        Set<String> keys = new HashSet<>();
        for (Variant variant : variants) {
            if (variant == null) {
                throw new FlagValidationException(
                        ValidationCode.INVALID_VARIANT,
                        "Variant cannot be null");
            }
            if (variant.valueType() != valueType) {
                throw new FlagValidationException(
                        ValidationCode.TYPE_MISMATCH,
                        "Variant type does not match feature flag type");
            }
            String key = requireKey(variant.key(), "variantKey");
            Variant normalizedVariant = switch (variant) {
                case BooleanVariant booleanVariant ->
                    new BooleanVariant(key, booleanVariant.value());
                case StringVariant stringVariant ->
                    new StringVariant(
                            key,
                            requireText(stringVariant.value(), "variantValue", 2048));
            };
            if (!keys.add(key)) {
                throw new FlagValidationException(
                        ValidationCode.DUPLICATE_VARIANT_KEY,
                        "Variant keys must be unique");
            }
            normalized.add(normalizedVariant);
        }
        return List.copyOf(normalized);
    }

    private static void ensureDefaultVariantExists(
            String defaultVariantKey,
            List<Variant> variants) {
        boolean exists = variants.stream()
                .anyMatch(variant -> variant.key().equals(defaultVariantKey));
        if (!exists) {
            throw new FlagValidationException(
                    ValidationCode.DEFAULT_VARIANT_NOT_FOUND,
                    "Default variant must reference a declared variant");
        }
    }

    private static void validateRemovalDate(
            LifecycleType lifecycleType,
            LocalDate expectedRemovalDate) {
        if ((lifecycleType == LifecycleType.RELEASE
                || lifecycleType == LifecycleType.EXPERIMENT)
                && expectedRemovalDate == null) {
            throw new FlagValidationException(
                    ValidationCode.REMOVAL_DATE_REQUIRED,
                    "Release and experiment flags require an expected removal date");
        }
    }

    private static void requireSupportedType(ValueType valueType) {
        if (!SUPPORTED_TYPES.contains(valueType)) {
            throw new FlagValidationException(
                    ValidationCode.UNSUPPORTED_VALUE_TYPE,
                    "Value type is reserved but not enabled yet");
        }
    }

    private static String requireKey(String value, String fieldName) {
        String normalized = requireText(value, fieldName, 63)
                .toLowerCase(Locale.ROOT);
        if (!KEY_PATTERN.matcher(normalized).matches()) {
            throw new FlagValidationException(
                    ValidationCode.INVALID_KEY,
                    fieldName + " must use lowercase letters, digits, and internal hyphens");
        }
        return normalized;
    }

    private static String requireText(
            String value,
            String fieldName,
            int maximumLength) {
        Objects.requireNonNull(value, fieldName + " is required");
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new FlagValidationException(
                    ValidationCode.INVALID_TEXT,
                    fieldName + " cannot be blank");
        }
        if (normalized.length() > maximumLength) {
            throw new FlagValidationException(
                    ValidationCode.INVALID_TEXT,
                    fieldName + " cannot exceed " + maximumLength + " characters");
        }
        return normalized;
    }

    private static String optionalText(
            String value,
            String fieldName,
            int maximumLength) {
        if (value == null) {
            return null;
        }
        return requireText(value, fieldName, maximumLength);
    }

    private static void ensureSameOrganization(
            TenantIdentity identity,
            UUID resourceOrganizationId) {
        if (!identity.organizationId().equals(resourceOrganizationId)) {
            throw TenantAccessException.resourceNotFound();
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public enum ValueType {
        BOOLEAN,
        STRING,
        NUMBER,
        JSON
    }

    public enum LifecycleType {
        RELEASE,
        EXPERIMENT,
        OPERATIONAL,
        KILL_SWITCH
    }

    public enum FlagState {
        ACTIVE,
        ARCHIVED
    }

    public enum ValidationCode {
        INVALID_KEY,
        INVALID_TEXT,
        UNSUPPORTED_VALUE_TYPE,
        VARIANTS_REQUIRED,
        INVALID_VARIANT,
        TYPE_MISMATCH,
        DUPLICATE_VARIANT_KEY,
        DEFAULT_VARIANT_NOT_FOUND,
        REMOVAL_DATE_REQUIRED,
        FLAG_KEY_ALREADY_EXISTS,
        CONCURRENT_MODIFICATION
    }

    public sealed interface Variant permits BooleanVariant, StringVariant {

        String key();

        ValueType valueType();
    }

    public record BooleanVariant(String key, boolean value) implements Variant {

        @Override
        public ValueType valueType() {
            return ValueType.BOOLEAN;
        }
    }

    public record StringVariant(String key, String value) implements Variant {

        @Override
        public ValueType valueType() {
            return ValueType.STRING;
        }
    }

    public record CreateFlagCommand(
            UUID projectId,
            String key,
            String displayName,
            String description,
            String ownerId,
            ValueType valueType,
            LifecycleType lifecycleType,
            LocalDate expectedRemovalDate,
            String defaultVariantKey,
            List<Variant> variants) {

        public CreateFlagCommand {
            Objects.requireNonNull(projectId, "projectId is required");
        }
    }

    public record FeatureFlag(
            UUID id,
            UUID organizationId,
            UUID projectId,
            String key,
            String displayName,
            String description,
            String ownerId,
            ValueType valueType,
            LifecycleType lifecycleType,
            LocalDate expectedRemovalDate,
            String defaultVariantKey,
            FlagState state,
            Instant archivedAt,
            Instant createdAt,
            Instant updatedAt,
            long version,
            List<Variant> variants) {

        public FeatureFlag {
            variants = List.copyOf(variants);
        }
    }

    public static final class FlagValidationException extends RuntimeException {

        private final ValidationCode code;

        public FlagValidationException(ValidationCode code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code is required");
        }

        public FlagValidationException(
                ValidationCode code,
                String message,
                Throwable cause) {
            super(message, cause);
            this.code = Objects.requireNonNull(code, "code is required");
        }

        public ValidationCode code() {
            return code;
        }
    }

    private record FeatureFlagRow(
            UUID id,
            UUID organizationId,
            UUID projectId,
            String key,
            String displayName,
            String description,
            String ownerId,
            ValueType valueType,
            LifecycleType lifecycleType,
            LocalDate expectedRemovalDate,
            String defaultVariantKey,
            FlagState state,
            Instant archivedAt,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }
}
