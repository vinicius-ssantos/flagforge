package io.github.viniciusssantos.flagforge.evaluation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.credentials.SdkCredentialService.SdkPrincipal;
import io.github.viniciusssantos.flagforge.evaluation.EvaluationApi.ValueType;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedFlag;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedSnapshot;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedValueType;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedVariant;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.SnapshotCodecException;

import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DatabaseEvaluationSnapshotProvider
        implements EvaluationSnapshotProvider {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PublishedSnapshotCodec snapshotCodec;

    public DatabaseEvaluationSnapshotProvider(
            NamedParameterJdbcTemplate jdbcTemplate,
            PublishedSnapshotCodec snapshotCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.snapshotCodec = snapshotCodec;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EvaluationSnapshot> load(
            SdkPrincipal principal,
            String flagKey) {
        Optional<PublishedSnapshotRow> stored = loadCurrentSnapshot(
                principal.organizationId(),
                principal.environmentId());
        if (stored.isEmpty()) {
            return Optional.empty();
        }

        PublishedSnapshotRow row = stored.get();
        PublishedSnapshot document;
        try {
            document = snapshotCodec.decode(row.payload(), row.checksum());
            validateIdentity(principal, row, document);
        } catch (SnapshotCodecException | IllegalStateException exception) {
            throw new DataRetrievalFailureException(
                    "Published evaluation snapshot is invalid",
                    exception);
        }

        Optional<PublishedFlag> requestedFlag = document.flags().stream()
                .filter(flag -> flag.key().equals(flagKey))
                .findFirst();
        if (requestedFlag.isEmpty()) {
            return Optional.empty();
        }

        PublishedFlag flag = requestedFlag.get();
        Map<String, VariantValue> variants = mapVariants(flag);
        String configurationVersion = "revision-"
                + document.revisionNumber()
                + "-sha256-"
                + row.checksum();

        return Optional.of(new EvaluationSnapshot(
                document.organizationId(),
                document.projectId(),
                document.environmentId(),
                configurationVersion,
                flag.enabled(),
                false,
                mapValueType(flag.valueType()),
                flag.defaultVariant(),
                variants,
                document.targetingConfiguration(),
                null));
    }

    private Optional<PublishedSnapshotRow> loadCurrentSnapshot(
            UUID organizationId,
            UUID environmentId) {
        String sql = """
                SELECT publication.project_id,
                       publication.current_revision_id,
                       publication.current_revision_number,
                       revision.snapshot_schema_version,
                       revision.algorithm_version,
                       revision.checksum,
                       snapshot.payload,
                       snapshot.payload_size
                FROM flagforge.environment_publication_state publication
                JOIN flagforge.configuration_revisions revision
                  ON revision.organization_id = publication.organization_id
                 AND revision.project_id = publication.project_id
                 AND revision.environment_id = publication.environment_id
                 AND revision.id = publication.current_revision_id
                 AND revision.revision_number = publication.current_revision_number
                JOIN flagforge.configuration_snapshots snapshot
                  ON snapshot.organization_id = revision.organization_id
                 AND snapshot.project_id = revision.project_id
                 AND snapshot.environment_id = revision.environment_id
                 AND snapshot.revision_id = revision.id
                 AND snapshot.revision_number = revision.revision_number
                WHERE publication.organization_id = :organizationId
                  AND publication.environment_id = :environmentId
                """;
        return jdbcTemplate.query(
                        sql,
                        Map.of(
                                "organizationId", organizationId,
                                "environmentId", environmentId),
                        (resultSet, rowNumber) -> new PublishedSnapshotRow(
                                resultSet.getObject("project_id", UUID.class),
                                resultSet.getObject("current_revision_id", UUID.class),
                                resultSet.getLong("current_revision_number"),
                                resultSet.getInt("snapshot_schema_version"),
                                resultSet.getString("algorithm_version"),
                                resultSet.getString("checksum"),
                                resultSet.getBytes("payload"),
                                resultSet.getInt("payload_size")))
                .stream()
                .findFirst();
    }

    private static Map<String, VariantValue> mapVariants(PublishedFlag flag) {
        Map<String, VariantValue> variants = new LinkedHashMap<>();
        for (PublishedVariant variant : flag.variants()) {
            Object value = switch (variant.valueType()) {
                case BOOLEAN -> variant.booleanValue();
                case STRING -> variant.stringValue();
            };
            variants.put(
                    variant.key(),
                    new VariantValue(mapValueType(variant.valueType()), value));
        }
        return Map.copyOf(variants);
    }

    private static ValueType mapValueType(PublishedValueType valueType) {
        return switch (valueType) {
            case BOOLEAN -> ValueType.BOOLEAN;
            case STRING -> ValueType.STRING;
        };
    }

    private static void validateIdentity(
            SdkPrincipal principal,
            PublishedSnapshotRow row,
            PublishedSnapshot document) {
        boolean matches = principal.organizationId().equals(document.organizationId())
                && principal.environmentId().equals(document.environmentId())
                && row.projectId().equals(document.projectId())
                && row.revisionNumber() == document.revisionNumber()
                && row.schemaVersion() == document.schemaVersion()
                && row.algorithmVersion().equals(document.algorithmVersion())
                && row.payloadSize() == row.payload().length;
        if (!matches) {
            throw new IllegalStateException(
                    "Published snapshot identity does not match its current pointer");
        }
    }

    private record PublishedSnapshotRow(
            UUID projectId,
            UUID revisionId,
            long revisionNumber,
            int schemaVersion,
            String algorithmVersion,
            String checksum,
            byte[] payload,
            int payloadSize) {

        private PublishedSnapshotRow {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
