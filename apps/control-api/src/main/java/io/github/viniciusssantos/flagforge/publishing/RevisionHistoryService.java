package io.github.viniciusssantos.flagforge.publishing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import io.github.viniciusssantos.flagforge.publishing.PublicationService.RevisionKind;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedFlag;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedSnapshot;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedVariant;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.SnapshotCodecException;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.AttributeValue;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.BooleanValue;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.Condition;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.EqualityCondition;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.FlagTarget;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.NumberValue;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.NumericCondition;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.Prerequisite;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.Segment;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.SegmentCondition;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.SemanticVersionCondition;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.StringSetCondition;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.StringValue;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.TargetingRule;
import io.github.viniciusssantos.flagforge.tenancy.ControlPlanePermission;
import io.github.viniciusssantos.flagforge.tenancy.Environment;
import io.github.viniciusssantos.flagforge.tenancy.TenantAccessException;
import io.github.viniciusssantos.flagforge.tenancy.TenantAuthorizationService;
import io.github.viniciusssantos.flagforge.tenancy.TenantHierarchyService;
import io.github.viniciusssantos.flagforge.tenancy.TenantIdentity;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RevisionHistoryService {

    private static final int DEFAULT_HISTORY_LIMIT = 100;
    private static final int MAX_HISTORY_LIMIT = 500;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TenantAuthorizationService authorizationService;
    private final TenantHierarchyService tenantHierarchyService;
    private final PublishedSnapshotCodec snapshotCodec;

    public RevisionHistoryService(
            NamedParameterJdbcTemplate jdbcTemplate,
            TenantAuthorizationService authorizationService,
            TenantHierarchyService tenantHierarchyService,
            PublishedSnapshotCodec snapshotCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
        this.tenantHierarchyService = tenantHierarchyService;
        this.snapshotCodec = snapshotCodec;
    }

    @Transactional(readOnly = true)
    public List<RevisionSummary> history(UUID environmentId) {
        return history(environmentId, DEFAULT_HISTORY_LIMIT);
    }

    @Transactional(readOnly = true)
    public List<RevisionSummary> history(UUID environmentId, int limit) {
        Environment environment = requireAuditEnvironment(environmentId);
        if (limit <= 0 || limit > MAX_HISTORY_LIMIT) {
            throw new IllegalArgumentException(
                    "Revision history limit must be between 1 and 500");
        }
        String sql = """
                SELECT revision.id,
                       revision.revision_number,
                       revision.revision_kind,
                       revision.source_revision_id,
                       revision.source_revision_number,
                       revision.snapshot_schema_version,
                       revision.algorithm_version,
                       revision.checksum,
                       snapshot.payload_size,
                       revision.published_by,
                       revision.correlation_id,
                       revision.published_at,
                       publication.current_revision_id = revision.id AS current
                FROM flagforge.configuration_revisions revision
                JOIN flagforge.configuration_snapshots snapshot
                  ON snapshot.organization_id = revision.organization_id
                 AND snapshot.project_id = revision.project_id
                 AND snapshot.environment_id = revision.environment_id
                 AND snapshot.revision_id = revision.id
                 AND snapshot.revision_number = revision.revision_number
                LEFT JOIN flagforge.environment_publication_state publication
                  ON publication.organization_id = revision.organization_id
                 AND publication.project_id = revision.project_id
                 AND publication.environment_id = revision.environment_id
                WHERE revision.organization_id = :organizationId
                  AND revision.project_id = :projectId
                  AND revision.environment_id = :environmentId
                ORDER BY revision.revision_number DESC
                LIMIT :limit
                """;
        return jdbcTemplate.query(
                sql,
                parameters(environment)
                        .addValue("limit", limit),
                (resultSet, rowNumber) -> mapSummary(resultSet));
    }

    @Transactional(readOnly = true)
    public RevisionDiff diff(
            UUID environmentId,
            long fromRevision,
            long toRevision) {
        if (fromRevision <= 0 || toRevision <= 0) {
            throw new IllegalArgumentException(
                    "Revision numbers must be positive");
        }
        Environment environment = requireAuditEnvironment(environmentId);
        SnapshotRevision from = load(environment, fromRevision);
        SnapshotRevision to = load(environment, toRevision);
        TreeMap<String, String> before = flatten(from.document());
        TreeMap<String, String> after = flatten(to.document());
        TreeSet<String> paths = new TreeSet<>(before.keySet());
        paths.addAll(after.keySet());

        List<Difference> differences = new ArrayList<>();
        for (String path : paths) {
            String beforeValue = before.get(path);
            String afterValue = after.get(path);
            if (Objects.equals(beforeValue, afterValue)) {
                continue;
            }
            DifferenceType type;
            if (beforeValue == null) {
                type = DifferenceType.ADDED;
            } else if (afterValue == null) {
                type = DifferenceType.REMOVED;
            } else {
                type = DifferenceType.CHANGED;
            }
            differences.add(new Difference(
                    path,
                    type,
                    beforeValue,
                    afterValue));
        }
        return new RevisionDiff(
                environment.id(),
                fromRevision,
                toRevision,
                from.checksum(),
                to.checksum(),
                List.copyOf(differences));
    }

    private Environment requireAuditEnvironment(UUID environmentId) {
        Objects.requireNonNull(environmentId, "environmentId is required");
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.AUDIT_READ);
        Environment environment = tenantHierarchyService.findEnvironment(environmentId);
        if (!identity.organizationId().equals(environment.organizationId())) {
            throw TenantAccessException.resourceNotFound();
        }
        return environment;
    }

    private SnapshotRevision load(Environment environment, long revisionNumber) {
        String sql = """
                SELECT revision.id,
                       revision.revision_number,
                       revision.checksum,
                       snapshot.payload
                FROM flagforge.configuration_revisions revision
                JOIN flagforge.configuration_snapshots snapshot
                  ON snapshot.organization_id = revision.organization_id
                 AND snapshot.project_id = revision.project_id
                 AND snapshot.environment_id = revision.environment_id
                 AND snapshot.revision_id = revision.id
                 AND snapshot.revision_number = revision.revision_number
                WHERE revision.organization_id = :organizationId
                  AND revision.project_id = :projectId
                  AND revision.environment_id = :environmentId
                  AND revision.revision_number = :revisionNumber
                """;
        SnapshotRow row = jdbcTemplate.query(
                        sql,
                        parameters(environment)
                                .addValue("revisionNumber", revisionNumber),
                        (resultSet, rowNumber) -> new SnapshotRow(
                                resultSet.getObject("id", UUID.class),
                                resultSet.getLong("revision_number"),
                                resultSet.getString("checksum"),
                                resultSet.getBytes("payload")))
                .stream()
                .findFirst()
                .orElseThrow(TenantAccessException::resourceNotFound);
        PublishedSnapshot document;
        try {
            document = snapshotCodec.decode(row.payload(), row.checksum());
        } catch (SnapshotCodecException exception) {
            throw new IllegalStateException(
                    "Immutable revision snapshot is invalid",
                    exception);
        }
        boolean identityMatches = environment.organizationId().equals(
                        document.organizationId())
                && environment.projectId().equals(document.projectId())
                && environment.id().equals(document.environmentId())
                && row.revisionNumber() == document.revisionNumber();
        if (!identityMatches) {
            throw new IllegalStateException(
                    "Immutable revision identity is inconsistent");
        }
        return new SnapshotRevision(
                row.revisionId(),
                row.revisionNumber(),
                row.checksum(),
                document);
    }

    private static TreeMap<String, String> flatten(PublishedSnapshot snapshot) {
        TreeMap<String, String> values = new TreeMap<>();
        for (PublishedFlag flag : snapshot.flags()) {
            String flagPath = "flags." + flag.key();
            values.put(flagPath + ".enabled", Boolean.toString(flag.enabled()));
            values.put(flagPath + ".valueType", flag.valueType().name());
            values.put(flagPath + ".defaultVariant", flag.defaultVariant());
            for (PublishedVariant variant : flag.variants()) {
                String variantPath = flagPath + ".variants." + variant.key();
                values.put(variantPath + ".valueType", variant.valueType().name());
                values.put(variantPath + ".value", variantValue(variant));
            }
        }
        for (FlagTarget target : snapshot.targetingConfiguration().flags()) {
            flattenTarget(values, target);
        }
        for (Segment segment : snapshot.targetingConfiguration().segments()) {
            flattenSegment(values, segment);
        }
        return values;
    }

    private static void flattenTarget(
            Map<String, String> values,
            FlagTarget target) {
        String flagPath = "flags." + target.key();
        for (Prerequisite prerequisite : target.prerequisites()) {
            values.put(
                    flagPath + ".prerequisites." + prerequisite.flagKey(),
                    prerequisite.expectedVariant());
        }
        for (TargetingRule rule : target.rules()) {
            String rulePath = flagPath + ".rules." + rule.key();
            values.put(rulePath + ".priority", Integer.toString(rule.priority()));
            values.put(rulePath + ".variant", rule.variantKey());
            flattenConditions(values, rulePath + ".conditions", rule.conditions());
        }
    }

    private static void flattenSegment(
            Map<String, String> values,
            Segment segment) {
        String segmentPath = "segments." + segment.key();
        values.put(
                segmentPath + ".includedTargetingKeys",
                sortedValues(segment.includedTargetingKeys()));
        values.put(
                segmentPath + ".excludedTargetingKeys",
                sortedValues(segment.excludedTargetingKeys()));
        flattenConditions(
                values,
                segmentPath + ".conditions",
                segment.conditions());
    }

    private static void flattenConditions(
            Map<String, String> values,
            String basePath,
            List<Condition> conditions) {
        List<String> canonical = conditions.stream()
                .map(RevisionHistoryService::conditionValue)
                .sorted()
                .toList();
        for (int index = 0; index < canonical.size(); index++) {
            values.put(basePath + "." + index, canonical.get(index));
        }
    }

    private static String conditionValue(Condition condition) {
        if (condition instanceof EqualityCondition equality) {
            return "EQUAL(" + equality.attribute() + ","
                    + attributeValue(equality.expected()) + ")";
        }
        if (condition instanceof StringSetCondition membership) {
            return "STRING_SET(" + membership.attribute() + ","
                    + sortedValues(membership.values()) + ")";
        }
        if (condition instanceof NumericCondition numeric) {
            return "NUMBER(" + numeric.attribute() + ","
                    + numeric.operator().name() + ","
                    + numeric.operand().toPlainString() + ")";
        }
        if (condition instanceof SemanticVersionCondition version) {
            return "SEMVER(" + version.attribute() + ","
                    + version.operator().name() + ","
                    + version.operand() + ")";
        }
        if (condition instanceof SegmentCondition segment) {
            return "SEGMENT(" + segment.segmentKey() + ","
                    + segment.negated() + ")";
        }
        throw new IllegalStateException(
                "Published targeting condition is unsupported");
    }

    private static String attributeValue(AttributeValue value) {
        if (value instanceof StringValue stringValue) {
            return "STRING:" + stringValue.value();
        }
        if (value instanceof NumberValue numberValue) {
            return "NUMBER:" + numberValue.value().toPlainString();
        }
        if (value instanceof BooleanValue booleanValue) {
            return "BOOLEAN:" + booleanValue.value();
        }
        throw new IllegalStateException(
                "Published targeting value is unsupported");
    }

    private static String variantValue(PublishedVariant variant) {
        return switch (variant.valueType()) {
            case BOOLEAN -> Boolean.toString(variant.booleanValue());
            case STRING -> variant.stringValue();
        };
    }

    private static String sortedValues(Set<String> values) {
        return values.stream().sorted().collect(Collectors.joining(","));
    }

    private static RevisionSummary mapSummary(ResultSet resultSet)
            throws SQLException {
        return new RevisionSummary(
                resultSet.getObject("id", UUID.class),
                resultSet.getLong("revision_number"),
                RevisionKind.valueOf(resultSet.getString("revision_kind")),
                resultSet.getObject("source_revision_id", UUID.class),
                nullableLong(resultSet, "source_revision_number"),
                resultSet.getInt("snapshot_schema_version"),
                resultSet.getString("algorithm_version"),
                resultSet.getString("checksum"),
                resultSet.getInt("payload_size"),
                resultSet.getString("published_by"),
                resultSet.getString("correlation_id"),
                resultSet.getTimestamp("published_at").toInstant(),
                resultSet.getBoolean("current"));
    }

    private static Long nullableLong(ResultSet resultSet, String column)
            throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static MapSqlParameterSource parameters(Environment environment) {
        return new MapSqlParameterSource()
                .addValue("organizationId", environment.organizationId())
                .addValue("projectId", environment.projectId())
                .addValue("environmentId", environment.id());
    }

    private record SnapshotRow(
            UUID revisionId,
            long revisionNumber,
            String checksum,
            byte[] payload) {

        private SnapshotRow {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    private record SnapshotRevision(
            UUID revisionId,
            long revisionNumber,
            String checksum,
            PublishedSnapshot document) {
    }

    public record RevisionSummary(
            UUID revisionId,
            long revisionNumber,
            RevisionKind revisionKind,
            UUID sourceRevisionId,
            Long sourceRevisionNumber,
            int snapshotSchemaVersion,
            String algorithmVersion,
            String checksum,
            int payloadBytes,
            String publishedBy,
            String correlationId,
            Instant publishedAt,
            boolean current) {
    }

    public record RevisionDiff(
            UUID environmentId,
            long fromRevision,
            long toRevision,
            String fromChecksum,
            String toChecksum,
            List<Difference> differences) {

        public RevisionDiff {
            differences = List.copyOf(differences);
        }
    }

    public record Difference(
            String path,
            DifferenceType type,
            String beforeValue,
            String afterValue) {
    }

    public enum DifferenceType {
        ADDED,
        REMOVED,
        CHANGED
    }
}
