package io.github.viniciusssantos.flagforge.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.tenancy.ControlPlanePermission;
import io.github.viniciusssantos.flagforge.tenancy.Environment;
import io.github.viniciusssantos.flagforge.tenancy.TenantAccessException;
import io.github.viniciusssantos.flagforge.tenancy.TenantAuthorizationService;
import io.github.viniciusssantos.flagforge.tenancy.TenantHierarchyService;
import io.github.viniciusssantos.flagforge.tenancy.TenantIdentity;

import org.slf4j.MDC;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditTrailService {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";
    private static final int MAX_DETAILS = 24;
    private static final int MAX_DETAIL_KEY_LENGTH = 80;
    private static final int MAX_DETAIL_VALUE_LENGTH = 512;
    private static final int DEFAULT_HISTORY_LIMIT = 100;
    private static final int MAX_HISTORY_LIMIT = 500;
    private static final List<String> FORBIDDEN_DETAIL_KEYS = List.of(
            "secret",
            "password",
            "plaintext",
            "token",
            "targetingkey",
            "evaluationcontext",
            "attributes");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TenantAuthorizationService authorizationService;
    private final TenantHierarchyService tenantHierarchyService;

    public AuditTrailService(
            NamedParameterJdbcTemplate jdbcTemplate,
            TenantAuthorizationService authorizationService,
            TenantHierarchyService tenantHierarchyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
        this.tenantHierarchyService = tenantHierarchyService;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditEvent append(AuditCommand command) {
        Objects.requireNonNull(command, "audit command is required");
        Map<String, String> details = sanitizeDetails(command.details());
        String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
        UUID eventId = UUID.randomUUID();
        String sql = """
                INSERT INTO flagforge.audit_events (
                    id,
                    organization_id,
                    project_id,
                    environment_id,
                    actor_id,
                    action,
                    resource_type,
                    resource_id,
                    revision_id,
                    revision_number,
                    correlation_id,
                    details,
                    occurred_at
                ) VALUES (
                    :id,
                    :organizationId,
                    :projectId,
                    :environmentId,
                    :actorId,
                    :action,
                    :resourceType,
                    :resourceId,
                    :revisionId,
                    :revisionNumber,
                    :correlationId,
                    CAST(:details AS jsonb),
                    :occurredAt
                )
                """;
        jdbcTemplate.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("id", eventId)
                        .addValue("organizationId", command.organizationId())
                        .addValue("projectId", command.projectId())
                        .addValue("environmentId", command.environmentId())
                        .addValue("actorId", command.actorId())
                        .addValue("action", command.action().name())
                        .addValue("resourceType", command.resourceType().name())
                        .addValue("resourceId", command.resourceId())
                        .addValue("revisionId", command.revisionId())
                        .addValue("revisionNumber", command.revisionNumber())
                        .addValue("correlationId", correlationId)
                        .addValue("details", toJson(details))
                        .addValue("occurredAt", Timestamp.from(command.occurredAt())));
        return new AuditEvent(
                eventId,
                command.organizationId(),
                command.projectId(),
                command.environmentId(),
                command.actorId(),
                command.action(),
                command.resourceType(),
                command.resourceId(),
                command.revisionId(),
                command.revisionNumber(),
                correlationId,
                details,
                command.occurredAt());
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> history(UUID environmentId) {
        return history(environmentId, DEFAULT_HISTORY_LIMIT);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> history(UUID environmentId, int limit) {
        Objects.requireNonNull(environmentId, "environmentId is required");
        if (limit <= 0 || limit > MAX_HISTORY_LIMIT) {
            throw new IllegalArgumentException(
                    "Audit history limit must be between 1 and 500");
        }
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.AUDIT_READ);
        Environment environment = tenantHierarchyService.findEnvironment(environmentId);
        ensureSameOrganization(identity, environment.organizationId());

        String sql = """
                SELECT id,
                       organization_id,
                       project_id,
                       environment_id,
                       actor_id,
                       action,
                       resource_type,
                       resource_id,
                       revision_id,
                       revision_number,
                       correlation_id,
                       details::text AS details_json,
                       occurred_at
                FROM flagforge.audit_events
                WHERE organization_id = :organizationId
                  AND (
                      environment_id = :environmentId
                      OR (
                          environment_id IS NULL
                          AND project_id = :projectId
                      )
                  )
                ORDER BY occurred_at DESC, id DESC
                LIMIT :limit
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("organizationId", environment.organizationId())
                        .addValue("projectId", environment.projectId())
                        .addValue("environmentId", environment.id())
                        .addValue("limit", limit),
                (resultSet, rowNumber) -> mapEvent(resultSet));
    }

    private static AuditEvent mapEvent(ResultSet resultSet) throws SQLException {
        return new AuditEvent(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("environment_id", UUID.class),
                resultSet.getString("actor_id"),
                AuditAction.valueOf(resultSet.getString("action")),
                AuditResourceType.valueOf(resultSet.getString("resource_type")),
                resultSet.getObject("resource_id", UUID.class),
                resultSet.getObject("revision_id", UUID.class),
                nullableLong(resultSet, "revision_number"),
                resultSet.getString("correlation_id"),
                Map.of("json", resultSet.getString("details_json")),
                resultSet.getTimestamp("occurred_at").toInstant());
    }

    private static Long nullableLong(ResultSet resultSet, String column)
            throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Map<String, String> sanitizeDetails(
            Map<String, String> requested) {
        Map<String, String> details = requested == null
                ? Map.of()
                : requested;
        if (details.size() > MAX_DETAILS) {
            throw new IllegalArgumentException(
                    "Audit details exceed the maximum entry count");
        }
        LinkedHashMap<String, String> sanitized = new LinkedHashMap<>();
        details.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String key = requireText(
                            entry.getKey(),
                            "audit detail key",
                            MAX_DETAIL_KEY_LENGTH);
                    String normalizedKey = key.toLowerCase(Locale.ROOT)
                            .replace("_", "")
                            .replace("-", "");
                    if (FORBIDDEN_DETAIL_KEYS.stream()
                            .anyMatch(normalizedKey::contains)) {
                        throw new IllegalArgumentException(
                                "Sensitive audit detail keys are forbidden");
                    }
                    String value = requireText(
                            entry.getValue(),
                            "audit detail value",
                            MAX_DETAIL_VALUE_LENGTH);
                    sanitized.put(key, value);
                });
        return Map.copyOf(sanitized);
    }

    private static String requireText(
            String value,
            String description,
            int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(description + " is invalid");
        }
        return value;
    }

    private static String toJson(Map<String, String> values) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                json.append(',');
            }
            json.append('"')
                    .append(escapeJson(entry.getKey()))
                    .append("\":\"")
                    .append(escapeJson(entry.getValue()))
                    .append('"');
            first = false;
        }
        return json.append('}').toString();
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static void ensureSameOrganization(
            TenantIdentity identity,
            UUID organizationId) {
        if (!identity.organizationId().equals(organizationId)) {
            throw TenantAccessException.resourceNotFound();
        }
    }

    public enum AuditAction {
        FEATURE_FLAG_CREATED,
        FEATURE_FLAG_ARCHIVED,
        SDK_CREDENTIAL_CREATED,
        SDK_CREDENTIAL_ROTATED,
        SDK_CREDENTIAL_REVOKED,
        CONFIGURATION_PUBLISHED,
        CONFIGURATION_ROLLED_BACK,
        CHANGE_REQUEST_CREATED,
        CHANGE_REQUEST_SUBMITTED,
        CHANGE_REQUEST_APPROVED,
        CHANGE_REQUEST_REJECTED
    }

    public enum AuditResourceType {
        FEATURE_FLAG,
        SDK_CREDENTIAL,
        CONFIGURATION_REVISION,
        CHANGE_REQUEST
    }

    public record AuditCommand(
            UUID organizationId,
            UUID projectId,
            UUID environmentId,
            String actorId,
            AuditAction action,
            AuditResourceType resourceType,
            UUID resourceId,
            UUID revisionId,
            Long revisionNumber,
            Map<String, String> details,
            Instant occurredAt) {

        public AuditCommand {
            Objects.requireNonNull(organizationId, "organizationId is required");
            Objects.requireNonNull(actorId, "actorId is required");
            Objects.requireNonNull(action, "action is required");
            Objects.requireNonNull(resourceType, "resourceType is required");
            Objects.requireNonNull(resourceId, "resourceId is required");
            Objects.requireNonNull(occurredAt, "occurredAt is required");
            boolean revisionConsistent = revisionId == null
                    ? revisionNumber == null
                    : revisionNumber != null;
            if (!revisionConsistent) {
                throw new IllegalArgumentException(
                        "revisionId and revisionNumber must be supplied together");
            }
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }

    public record AuditEvent(
            UUID id,
            UUID organizationId,
            UUID projectId,
            UUID environmentId,
            String actorId,
            AuditAction action,
            AuditResourceType resourceType,
            UUID resourceId,
            UUID revisionId,
            Long revisionNumber,
            String correlationId,
            Map<String, String> details,
            Instant occurredAt) {
    }
}
