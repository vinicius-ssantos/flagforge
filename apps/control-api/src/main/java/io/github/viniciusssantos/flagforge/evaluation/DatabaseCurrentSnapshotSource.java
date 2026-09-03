package io.github.viniciusssantos.flagforge.evaluation;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedSnapshot;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.SnapshotCodecException;

import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the effective configuration from the source of truth.
 *
 * <p>Resolves the environment's current revision pointer, loads the exact payload it pins, verifies
 * the checksum, and confirms the decoded document describes the environment that asked for it. A
 * cache in front of this must not skip those checks; it caches their result.
 */
@Component
class DatabaseCurrentSnapshotSource implements CurrentSnapshotSource {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PublishedSnapshotCodec snapshotCodec;

    DatabaseCurrentSnapshotSource(
            NamedParameterJdbcTemplate jdbcTemplate,
            PublishedSnapshotCodec snapshotCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.snapshotCodec = snapshotCodec;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CurrentSnapshot> loadCurrent(UUID organizationId, UUID environmentId) {
        return loadCurrentIdentity(organizationId, environmentId)
                .map(identity -> loadDocument(organizationId, environmentId, identity));
    }

    /**
     * Resolves the pointer without reading the payload.
     *
     * <p>Deliberately cheap: this runs whenever a cached pointer needs confirming, which is far
     * more often than a document actually changes.
     */
    @Transactional(readOnly = true)
    public Optional<SnapshotIdentity> loadCurrentIdentity(UUID organizationId, UUID environmentId) {
        String sql = """
                SELECT publication.project_id,
                       publication.current_revision_id,
                       publication.current_revision_number,
                       revision.snapshot_schema_version,
                       revision.algorithm_version,
                       revision.checksum
                FROM flagforge.environment_publication_state publication
                JOIN flagforge.configuration_revisions revision
                  ON revision.organization_id = publication.organization_id
                 AND revision.project_id = publication.project_id
                 AND revision.environment_id = publication.environment_id
                 AND revision.id = publication.current_revision_id
                 AND revision.revision_number = publication.current_revision_number
                WHERE publication.organization_id = :organizationId
                  AND publication.environment_id = :environmentId
                """;
        return jdbcTemplate.query(
                        sql,
                        Map.of(
                                "organizationId", organizationId,
                                "environmentId", environmentId),
                        (resultSet, rowNumber) -> new SnapshotIdentity(
                                resultSet.getObject("project_id", UUID.class),
                                resultSet.getObject("current_revision_id", UUID.class),
                                resultSet.getLong("current_revision_number"),
                                resultSet.getInt("snapshot_schema_version"),
                                resultSet.getString("algorithm_version"),
                                resultSet.getString("checksum")))
                .stream()
                .findFirst();
    }

    /**
     * Loads and verifies the payload the pointer names.
     *
     * <p>The checksum and identity checks live here rather than in the caller, so nothing can cache
     * a document that was never verified.
     */
    @Transactional(readOnly = true)
    public CurrentSnapshot loadDocument(
            UUID organizationId,
            UUID environmentId,
            SnapshotIdentity identity) {
        PublishedSnapshotRow row = loadPayload(organizationId, environmentId, identity)
                .orElseThrow(() -> new DataRetrievalFailureException(
                        "Published evaluation snapshot is missing for its current pointer"));
        PublishedSnapshot document;
        try {
            document = snapshotCodec.decode(row.payload(), identity.checksum());
            validateIdentity(organizationId, environmentId, identity, row, document);
        } catch (SnapshotCodecException | IllegalStateException exception) {
            throw new DataRetrievalFailureException(
                    "Published evaluation snapshot is invalid",
                    exception);
        }

        return new CurrentSnapshot(
                identity.projectId(),
                identity.revisionId(),
                identity.revisionNumber(),
                identity.schemaVersion(),
                identity.algorithmVersion(),
                identity.checksum(),
                document,
                false);
    }

    private Optional<PublishedSnapshotRow> loadPayload(
            UUID organizationId,
            UUID environmentId,
            SnapshotIdentity identity) {
        String sql = """
                SELECT snapshot.payload,
                       snapshot.payload_size
                FROM flagforge.configuration_snapshots snapshot
                WHERE snapshot.organization_id = :organizationId
                  AND snapshot.project_id = :projectId
                  AND snapshot.environment_id = :environmentId
                  AND snapshot.revision_id = :revisionId
                  AND snapshot.revision_number = :revisionNumber
                """;
        return jdbcTemplate.query(
                        sql,
                        Map.of(
                                "organizationId", organizationId,
                                "projectId", identity.projectId(),
                                "environmentId", environmentId,
                                "revisionId", identity.revisionId(),
                                "revisionNumber", identity.revisionNumber()),
                        (resultSet, rowNumber) -> new PublishedSnapshotRow(
                                resultSet.getBytes("payload"),
                                resultSet.getInt("payload_size")))
                .stream()
                .findFirst();
    }

    private static void validateIdentity(
            UUID organizationId,
            UUID environmentId,
            SnapshotIdentity identity,
            PublishedSnapshotRow row,
            PublishedSnapshot document) {
        boolean matches = organizationId.equals(document.organizationId())
                && environmentId.equals(document.environmentId())
                && identity.projectId().equals(document.projectId())
                && identity.revisionNumber() == document.revisionNumber()
                && identity.schemaVersion() == document.schemaVersion()
                && identity.algorithmVersion().equals(document.algorithmVersion())
                && row.payloadSize() == row.payload().length;
        if (!matches) {
            throw new IllegalStateException(
                    "Published snapshot identity does not match its current pointer");
        }
    }

    private record PublishedSnapshotRow(byte[] payload, int payloadSize) {

        private PublishedSnapshotRow {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
