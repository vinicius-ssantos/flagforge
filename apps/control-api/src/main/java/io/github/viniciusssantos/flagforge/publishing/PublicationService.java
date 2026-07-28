package io.github.viniciusssantos.flagforge.publishing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.audit.AuditTrailService;
import io.github.viniciusssantos.flagforge.audit.AuditTrailService.AuditAction;
import io.github.viniciusssantos.flagforge.audit.AuditTrailService.AuditCommand;
import io.github.viniciusssantos.flagforge.audit.AuditTrailService.AuditResourceType;
import io.github.viniciusssantos.flagforge.publishing.PublicationGraphValidator.PublicationGraphException;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.EncodedSnapshot;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedFlag;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedSnapshot;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedValueType;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedVariant;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.SnapshotCodecException;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.TargetingConfiguration;
import io.github.viniciusssantos.flagforge.tenancy.ControlPlanePermission;
import io.github.viniciusssantos.flagforge.tenancy.Environment;
import io.github.viniciusssantos.flagforge.tenancy.TenantAccessException;
import io.github.viniciusssantos.flagforge.tenancy.TenantAuthorizationService;
import io.github.viniciusssantos.flagforge.tenancy.TenantHierarchyService;
import io.github.viniciusssantos.flagforge.tenancy.TenantIdentity;

import org.slf4j.MDC;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicationService {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";
    private static final String PUBLISHED_EVENT = "CONFIGURATION_PUBLISHED";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TenantAuthorizationService authorizationService;
    private final TenantHierarchyService tenantHierarchyService;
    private final PublishedSnapshotCodec snapshotCodec;
    private final PublicationGraphValidator publicationGraphValidator;
    private final AuditTrailService auditTrailService;

    public PublicationService(
            NamedParameterJdbcTemplate jdbcTemplate,
            TenantAuthorizationService authorizationService,
            TenantHierarchyService tenantHierarchyService,
            PublishedSnapshotCodec snapshotCodec,
            PublicationGraphValidator publicationGraphValidator,
            AuditTrailService auditTrailService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
        this.tenantHierarchyService = tenantHierarchyService;
        this.snapshotCodec = snapshotCodec;
        this.publicationGraphValidator = publicationGraphValidator;
        this.auditTrailService = auditTrailService;
    }

    @Transactional
    public PublishedRevision publish(UUID environmentId, long expectedVersion) {
        return publish(environmentId, expectedVersion, null);
    }

    @Transactional
    public PublishedRevision publish(
            UUID environmentId,
            long expectedVersion,
            TargetingConfiguration targetingConfiguration) {
        Objects.requireNonNull(environmentId, "environmentId is required");
        if (expectedVersion < 0) {
            throw new PublicationException(
                    PublicationError.INVALID_EXPECTED_VERSION,
                    "Expected publication version cannot be negative");
        }
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.ENVIRONMENT_WRITE);
        Environment environment = tenantHierarchyService.findEnvironment(environmentId);
        ensureSameOrganization(identity, environment.organizationId());
        lockEnvironment(environment);

        PublicationState state = currentPublicationState(environment);
        if (state.publicationVersion() != expectedVersion) {
            throw PublicationException.versionConflict(expectedVersion, state);
        }
        long revisionNumber = state.currentRevisionNumber() + 1;
        long publicationVersion = expectedVersion + 1;
        List<PublishedFlag> flags = compileCandidate(
                environment.organizationId(),
                environment.projectId());
        TargetingConfiguration validatedConfiguration;
        try {
            validatedConfiguration = publicationGraphValidator.validate(
                    flags,
                    targetingConfiguration);
        } catch (PublicationGraphException exception) {
            throw PublicationException.invalidGraph(exception);
        }
        PublishedSnapshot snapshot = new PublishedSnapshot(
                PublishedSnapshotCodec.SCHEMA_VERSION,
                environment.organizationId(),
                environment.projectId(),
                environment.id(),
                revisionNumber,
                PublishedSnapshotCodec.ALGORITHM_VERSION,
                flags,
                validatedConfiguration);

        EncodedSnapshot encoded;
        try {
            encoded = snapshotCodec.encode(snapshot);
        } catch (SnapshotCodecException exception) {
            throw new PublicationException(
                    PublicationError.INVALID_CONFIGURATION,
                    "Candidate configuration cannot be published",
                    exception);
        }

        UUID revisionId = UUID.randomUUID();
        Instant publishedAt = now();
        String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
        insertRevision(
                revisionId,
                environment,
                revisionNumber,
                encoded,
                identity.actorId(),
                correlationId,
                publishedAt);
        insertSnapshot(
                revisionId,
                environment,
                revisionNumber,
                encoded,
                publishedAt);
        insertAuditEvent(
                revisionId,
                environment,
                revisionNumber,
                identity.actorId(),
                correlationId,
                publishedAt);
        insertOutboxEvent(
                revisionId,
                environment,
                revisionNumber,
                encoded.checksum(),
                publishedAt);
        moveCurrentPointer(
                revisionId,
                environment,
                revisionNumber,
                expectedVersion,
                publicationVersion,
                publishedAt);

        return new PublishedRevision(
                revisionId,
                environment.organizationId(),
                environment.projectId(),
                environment.id(),
                revisionNumber,
                publicationVersion,
                PublishedSnapshotCodec.SCHEMA_VERSION,
                PublishedSnapshotCodec.ALGORITHM_VERSION,
                encoded.checksum(),
                encoded.payload().length,
                identity.actorId(),
                correlationId,
                publishedAt);
    }

    @Transactional(readOnly = true)
    public Optional<PublishedRevision> current(UUID environmentId) {
        Objects.requireNonNull(environmentId, "environmentId is required");
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.ENVIRONMENT_READ);
        Environment environment = tenantHierarchyService.findEnvironment(environmentId);
        ensureSameOrganization(identity, environment.organizationId());

        String sql = """
                SELECT revision.id,
                       revision.organization_id,
                       revision.project_id,
                       revision.environment_id,
                       revision.revision_number,
                       publication.pointer_version,
                       revision.snapshot_schema_version,
                       revision.algorithm_version,
                       revision.checksum,
                       snapshot.payload_size,
                       revision.published_by,
                       revision.correlation_id,
                       revision.published_at
                FROM flagforge.environment_publication_state publication
                JOIN flagforge.configuration_revisions revision
                  ON revision.organization_id = publication.organization_id
                 AND revision.project_id = publication.project_id
                 AND revision.environment_id = publication.environment_id
                 AND revision.id = publication.current_revision_id
                 AND revision.revision_number = publication.current_revision_number
                JOIN flagforge.configuration_snapshots snapshot
                  ON snapshot.revision_id = revision.id
                WHERE publication.organization_id = :organizationId
                  AND publication.project_id = :projectId
                  AND publication.environment_id = :environmentId
                """;
        return jdbcTemplate.query(
                        sql,
                        Map.of(
                                "organizationId", environment.organizationId(),
                                "projectId", environment.projectId(),
                                "environmentId", environment.id()),
                        (resultSet, rowNumber) -> mapPublishedRevision(resultSet))
                .stream()
                .findFirst();
    }

    private List<PublishedFlag> compileCandidate(
            UUID organizationId,
            UUID projectId) {
        String flagsSql = """
                SELECT id,
                       flag_key,
                       value_type,
                       default_variant_key
                FROM flagforge.feature_flags
                WHERE organization_id = :organizationId
                  AND project_id = :projectId
                  AND state = 'ACTIVE'
                ORDER BY flag_key
                """;
        Map<String, Object> parameters = Map.of(
                "organizationId", organizationId,
                "projectId", projectId);
        LinkedHashMap<UUID, MutableFlag> flags = new LinkedHashMap<>();
        jdbcTemplate.query(
                flagsSql,
                parameters,
                (RowCallbackHandler) resultSet -> {
                    UUID id = resultSet.getObject("id", UUID.class);
                    flags.put(
                            id,
                            new MutableFlag(
                                    resultSet.getString("flag_key"),
                                    parseValueType(resultSet.getString("value_type")),
                                    resultSet.getString("default_variant_key"),
                                    new ArrayList<>()));
                });
        if (flags.isEmpty()) {
            return List.of();
        }

        String variantsSql = """
                SELECT variant.flag_id,
                       variant.variant_key,
                       variant.value_type,
                       variant.boolean_value,
                       variant.string_value
                FROM flagforge.feature_flag_variants variant
                JOIN flagforge.feature_flags flag
                  ON flag.organization_id = variant.organization_id
                 AND flag.project_id = variant.project_id
                 AND flag.id = variant.flag_id
                WHERE variant.organization_id = :organizationId
                  AND variant.project_id = :projectId
                  AND flag.state = 'ACTIVE'
                ORDER BY flag.flag_key, variant.variant_key
                """;
        jdbcTemplate.query(
                variantsSql,
                parameters,
                (RowCallbackHandler) resultSet -> addVariant(flags, resultSet));

        List<PublishedFlag> compiled = new ArrayList<>(flags.size());
        for (MutableFlag flag : flags.values()) {
            if (flag.variants().isEmpty()) {
                throw invalidConfiguration(
                        "Every active feature flag must declare at least one variant");
            }
            compiled.add(new PublishedFlag(
                    flag.key(),
                    flag.valueType(),
                    true,
                    flag.defaultVariant(),
                    flag.variants()));
        }
        return List.copyOf(compiled);
    }

    private static void addVariant(
            Map<UUID, MutableFlag> flags,
            ResultSet resultSet) throws SQLException {
        UUID flagId = resultSet.getObject("flag_id", UUID.class);
        MutableFlag flag = flags.get(flagId);
        if (flag == null) {
            throw invalidConfiguration(
                    "Variant references a flag outside the publication candidate");
        }
        PublishedValueType valueType = parseValueType(
                resultSet.getString("value_type"));
        PublishedVariant variant = switch (valueType) {
            case BOOLEAN -> PublishedVariant.booleanValue(
                    resultSet.getString("variant_key"),
                    resultSet.getBoolean("boolean_value"));
            case STRING -> PublishedVariant.stringValue(
                    resultSet.getString("variant_key"),
                    resultSet.getString("string_value"));
        };
        flag.variants().add(variant);
    }

    private void lockEnvironment(Environment environment) {
        String sql = """
                SELECT id
                FROM flagforge.environments
                WHERE organization_id = :organizationId
                  AND project_id = :projectId
                  AND id = :environmentId
                FOR UPDATE
                """;
        List<UUID> locked = jdbcTemplate.query(
                sql,
                Map.of(
                        "organizationId", environment.organizationId(),
                        "projectId", environment.projectId(),
                        "environmentId", environment.id()),
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class));
        if (locked.size() != 1) {
            throw TenantAccessException.resourceNotFound();
        }
    }

    private PublicationState currentPublicationState(Environment environment) {
        String sql = """
                SELECT publication.current_revision_id,
                       publication.current_revision_number,
                       publication.pointer_version,
                       revision.checksum,
                       publication.updated_at
                FROM flagforge.environment_publication_state publication
                JOIN flagforge.configuration_revisions revision
                  ON revision.organization_id = publication.organization_id
                 AND revision.project_id = publication.project_id
                 AND revision.environment_id = publication.environment_id
                 AND revision.id = publication.current_revision_id
                 AND revision.revision_number = publication.current_revision_number
                WHERE publication.organization_id = :organizationId
                  AND publication.project_id = :projectId
                  AND publication.environment_id = :environmentId
                FOR UPDATE OF publication
                """;
        return jdbcTemplate.query(
                        sql,
                        Map.of(
                                "organizationId", environment.organizationId(),
                                "projectId", environment.projectId(),
                                "environmentId", environment.id()),
                        (resultSet, rowNumber) -> new PublicationState(
                                resultSet.getObject("current_revision_id", UUID.class),
                                resultSet.getLong("current_revision_number"),
                                resultSet.getLong("pointer_version"),
                                resultSet.getString("checksum"),
                                resultSet.getTimestamp("updated_at").toInstant()))
                .stream()
                .findFirst()
                .orElseGet(PublicationState::unpublished);
    }

    private void insertRevision(
            UUID revisionId,
            Environment environment,
            long revisionNumber,
            EncodedSnapshot encoded,
            String actorId,
            String correlationId,
            Instant publishedAt) {
        String sql = """
                INSERT INTO flagforge.configuration_revisions (
                    id,
                    organization_id,
                    project_id,
                    environment_id,
                    revision_number,
                    snapshot_schema_version,
                    algorithm_version,
                    checksum,
                    published_by,
                    correlation_id,
                    published_at
                ) VALUES (
                    :id,
                    :organizationId,
                    :projectId,
                    :environmentId,
                    :revisionNumber,
                    :schemaVersion,
                    :algorithmVersion,
                    :checksum,
                    :publishedBy,
                    :correlationId,
                    :publishedAt
                )
                """;
        jdbcTemplate.update(
                sql,
                baseParameters(revisionId, environment, revisionNumber, publishedAt)
                        .addValue("schemaVersion", PublishedSnapshotCodec.SCHEMA_VERSION)
                        .addValue("algorithmVersion", PublishedSnapshotCodec.ALGORITHM_VERSION)
                        .addValue("checksum", encoded.checksum())
                        .addValue("publishedBy", actorId)
                        .addValue("correlationId", correlationId));
    }

    private void insertSnapshot(
            UUID revisionId,
            Environment environment,
            long revisionNumber,
            EncodedSnapshot encoded,
            Instant publishedAt) {
        byte[] payload = encoded.payload();
        String sql = """
                INSERT INTO flagforge.configuration_snapshots (
                    revision_id,
                    organization_id,
                    project_id,
                    environment_id,
                    revision_number,
                    snapshot_schema_version,
                    algorithm_version,
                    checksum,
                    payload,
                    payload_size,
                    created_at
                ) VALUES (
                    :id,
                    :organizationId,
                    :projectId,
                    :environmentId,
                    :revisionNumber,
                    :schemaVersion,
                    :algorithmVersion,
                    :checksum,
                    :payload,
                    :payloadSize,
                    :publishedAt
                )
                """;
        jdbcTemplate.update(
                sql,
                baseParameters(revisionId, environment, revisionNumber, publishedAt)
                        .addValue("schemaVersion", PublishedSnapshotCodec.SCHEMA_VERSION)
                        .addValue("algorithmVersion", PublishedSnapshotCodec.ALGORITHM_VERSION)
                        .addValue("checksum", encoded.checksum())
                        .addValue("payload", payload)
                        .addValue("payloadSize", payload.length));
    }

    private void insertAuditEvent(
            UUID revisionId,
            Environment environment,
            long revisionNumber,
            String actorId,
            String correlationId,
            Instant publishedAt) {
        String sql = """
                INSERT INTO flagforge.publication_audit_events (
                    id,
                    organization_id,
                    project_id,
                    environment_id,
                    revision_id,
                    revision_number,
                    actor_id,
                    action,
                    correlation_id,
                    occurred_at
                ) VALUES (
                    :auditId,
                    :organizationId,
                    :projectId,
                    :environmentId,
                    :id,
                    :revisionNumber,
                    :actorId,
                    :action,
                    :correlationId,
                    :publishedAt
                )
                """;
        jdbcTemplate.update(
                sql,
                baseParameters(revisionId, environment, revisionNumber, publishedAt)
                        .addValue("auditId", UUID.randomUUID())
                        .addValue("actorId", actorId)
                        .addValue("action", PUBLISHED_EVENT)
                        .addValue("correlationId", correlationId));
    }

    private void insertOutboxEvent(
            UUID revisionId,
            Environment environment,
            long revisionNumber,
            String checksum,
            Instant publishedAt) {
        String payload = outboxPayload(
                revisionId,
                environment,
                revisionNumber,
                checksum,
                publishedAt);
        String sql = """
                INSERT INTO flagforge.configuration_outbox (
                    id,
                    organization_id,
                    project_id,
                    environment_id,
                    revision_id,
                    revision_number,
                    event_type,
                    checksum,
                    payload,
                    status,
                    delivery_attempts,
                    occurred_at,
                    available_at
                ) VALUES (
                    :outboxId,
                    :organizationId,
                    :projectId,
                    :environmentId,
                    :id,
                    :revisionNumber,
                    :eventType,
                    :checksum,
                    CAST(:payload AS jsonb),
                    'PENDING',
                    0,
                    :publishedAt,
                    :publishedAt
                )
                """;
        jdbcTemplate.update(
                sql,
                baseParameters(revisionId, environment, revisionNumber, publishedAt)
                        .addValue("outboxId", UUID.randomUUID())
                        .addValue("eventType", PUBLISHED_EVENT)
                        .addValue("checksum", checksum)
                        .addValue("payload", payload));
    }

    private void moveCurrentPointer(
            UUID revisionId,
            Environment environment,
            long revisionNumber,
            long expectedVersion,
            long publicationVersion,
            Instant publishedAt) {
        String sql = """
                INSERT INTO flagforge.environment_publication_state (
                    organization_id,
                    project_id,
                    environment_id,
                    current_revision_id,
                    current_revision_number,
                    pointer_version,
                    updated_at
                ) VALUES (
                    :organizationId,
                    :projectId,
                    :environmentId,
                    :id,
                    :revisionNumber,
                    :publicationVersion,
                    :publishedAt
                )
                ON CONFLICT (organization_id, project_id, environment_id)
                DO UPDATE SET
                    current_revision_id = EXCLUDED.current_revision_id,
                    current_revision_number = EXCLUDED.current_revision_number,
                    pointer_version = EXCLUDED.pointer_version,
                    updated_at = EXCLUDED.updated_at
                WHERE flagforge.environment_publication_state.pointer_version =
                    :expectedVersion
                """;
        int updated = jdbcTemplate.update(
                sql,
                baseParameters(revisionId, environment, revisionNumber, publishedAt)
                        .addValue("expectedVersion", expectedVersion)
                        .addValue("publicationVersion", publicationVersion));
        if (updated != 1) {
            throw PublicationException.versionConflict(
                    expectedVersion,
                    currentPublicationState(environment));
        }
    }

    private static MapSqlParameterSource baseParameters(
            UUID revisionId,
            Environment environment,
            long revisionNumber,
            Instant publishedAt) {
        return new MapSqlParameterSource()
                .addValue("id", revisionId)
                .addValue("organizationId", environment.organizationId())
                .addValue("projectId", environment.projectId())
                .addValue("environmentId", environment.id())
                .addValue("revisionNumber", revisionNumber)
                .addValue("publishedAt", Timestamp.from(publishedAt));
    }

    private static String outboxPayload(
            UUID revisionId,
            Environment environment,
            long revisionNumber,
            String checksum,
            Instant publishedAt) {
        return "{" +
                "\"eventType\":\"" + PUBLISHED_EVENT + "\"," +
                "\"revisionId\":\"" + revisionId + "\"," +
                "\"organizationId\":\"" + environment.organizationId() + "\"," +
                "\"projectId\":\"" + environment.projectId() + "\"," +
                "\"environmentId\":\"" + environment.id() + "\"," +
                "\"revisionNumber\":" + revisionNumber + "," +
                "\"checksum\":\"" + checksum + "\"," +
                "\"publishedAt\":\"" + publishedAt + "\"" +
                "}";
    }

    private static PublishedRevision mapPublishedRevision(ResultSet resultSet)
            throws SQLException {
        return new PublishedRevision(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("environment_id", UUID.class),
                resultSet.getLong("revision_number"),
                resultSet.getLong("pointer_version"),
                resultSet.getInt("snapshot_schema_version"),
                resultSet.getString("algorithm_version"),
                resultSet.getString("checksum"),
                resultSet.getInt("payload_size"),
                resultSet.getString("published_by"),
                resultSet.getString("correlation_id"),
                resultSet.getTimestamp("published_at").toInstant());
    }

    private static PublishedValueType parseValueType(String valueType) {
        try {
            return PublishedValueType.valueOf(valueType);
        } catch (IllegalArgumentException exception) {
            throw invalidConfiguration(
                    "Feature flag value type is not supported by snapshot schema v1",
                    exception);
        }
    }

    private static void ensureSameOrganization(
            TenantIdentity identity,
            UUID organizationId) {
        if (!identity.organizationId().equals(organizationId)) {
            throw TenantAccessException.resourceNotFound();
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static PublicationException invalidConfiguration(String message) {
        return new PublicationException(
                PublicationError.INVALID_CONFIGURATION,
                message);
    }

    private static PublicationException invalidConfiguration(
            String message,
            Throwable cause) {
        return new PublicationException(
                PublicationError.INVALID_CONFIGURATION,
                message,
                cause);
    }

    private record MutableFlag(
            String key,
            PublishedValueType valueType,
            String defaultVariant,
            List<PublishedVariant> variants) {
    }

    private record PublicationState(
            UUID currentRevisionId,
            long currentRevisionNumber,
            long publicationVersion,
            String currentChecksum,
            Instant updatedAt) {

        private static PublicationState unpublished() {
            return new PublicationState(null, 0, 0, null, null);
        }
    }

    public record PublishedRevision(
            UUID revisionId,
            UUID organizationId,
            UUID projectId,
            UUID environmentId,
            long revisionNumber,
            long publicationVersion,
            int snapshotSchemaVersion,
            String algorithmVersion,
            String checksum,
            int payloadBytes,
            String publishedBy,
            String correlationId,
            Instant publishedAt) {
    }

    public enum PublicationError {
        INVALID_CONFIGURATION,
        INVALID_EXPECTED_VERSION,
        VERSION_CONFLICT
    }

    public static final class PublicationException extends RuntimeException {

        private final PublicationError code;
        private final Long expectedVersion;
        private final Long currentVersion;
        private final UUID currentRevisionId;
        private final Long currentRevisionNumber;
        private final String currentChecksum;
        private final Instant currentUpdatedAt;
        private final String validationCode;

        public PublicationException(PublicationError code, String message) {
            this(code, message, null, null, null, null, null, null, null, null);
        }

        public PublicationException(
                PublicationError code,
                String message,
                Throwable cause) {
            this(code, message, cause, null, null, null, null, null, null, null);
        }

        private PublicationException(
                PublicationError code,
                String message,
                Throwable cause,
                Long expectedVersion,
                Long currentVersion,
                UUID currentRevisionId,
                Long currentRevisionNumber,
                String currentChecksum,
                Instant currentUpdatedAt,
                String validationCode) {
            super(message, cause);
            this.code = Objects.requireNonNull(code, "code is required");
            this.expectedVersion = expectedVersion;
            this.currentVersion = currentVersion;
            this.currentRevisionId = currentRevisionId;
            this.currentRevisionNumber = currentRevisionNumber;
            this.currentChecksum = currentChecksum;
            this.currentUpdatedAt = currentUpdatedAt;
            this.validationCode = validationCode;
        }

        private static PublicationException invalidGraph(
                PublicationGraphException exception) {
            return new PublicationException(
                    PublicationError.INVALID_CONFIGURATION,
                    "Candidate targeting graph cannot be published",
                    exception,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    exception.validationCode());
        }

        private static PublicationException versionConflict(
                long expectedVersion,
                PublicationState state) {
            return new PublicationException(
                    PublicationError.VERSION_CONFLICT,
                    "Publication version conflict; reload the environment before publishing",
                    null,
                    expectedVersion,
                    state.publicationVersion(),
                    state.currentRevisionId(),
                    state.currentRevisionNumber(),
                    state.currentChecksum(),
                    state.updatedAt(),
                    null);
        }

        public PublicationError code() {
            return code;
        }

        public Long expectedVersion() {
            return expectedVersion;
        }

        public Long currentVersion() {
            return currentVersion;
        }

        public UUID currentRevisionId() {
            return currentRevisionId;
        }

        public Long currentRevisionNumber() {
            return currentRevisionNumber;
        }

        public String currentChecksum() {
            return currentChecksum;
        }

        public Instant currentUpdatedAt() {
            return currentUpdatedAt;
        }

        public String validationCode() {
            return validationCode;
        }
    }
}
