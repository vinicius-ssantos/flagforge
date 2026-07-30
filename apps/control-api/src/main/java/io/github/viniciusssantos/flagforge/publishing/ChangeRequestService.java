package io.github.viniciusssantos.flagforge.publishing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Objects;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.audit.AuditTrailService;
import io.github.viniciusssantos.flagforge.audit.AuditTrailService.AuditAction;
import io.github.viniciusssantos.flagforge.audit.AuditTrailService.AuditCommand;
import io.github.viniciusssantos.flagforge.audit.AuditTrailService.AuditResourceType;
import io.github.viniciusssantos.flagforge.publishing.PublicationService.PreparedPublication;
import io.github.viniciusssantos.flagforge.publishing.PublicationService.PublicationException;
import io.github.viniciusssantos.flagforge.publishing.PublicationService.PublishedRevision;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedFlag;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedSnapshot;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedVariant;
import io.github.viniciusssantos.flagforge.tenancy.ControlPlanePermission;
import io.github.viniciusssantos.flagforge.tenancy.Environment;
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
public class ChangeRequestService {

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_NOTE_LENGTH = 2000;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TenantAuthorizationService authorizationService;
    private final TenantHierarchyService tenantHierarchyService;
    private final EnvironmentApprovalPolicyService policyService;
    private final PublicationService publicationService;
    private final PublishedSnapshotCodec snapshotCodec;
    private final AuditTrailService auditTrailService;

    public ChangeRequestService(
            NamedParameterJdbcTemplate jdbcTemplate,
            TenantAuthorizationService authorizationService,
            TenantHierarchyService tenantHierarchyService,
            EnvironmentApprovalPolicyService policyService,
            PublicationService publicationService,
            PublishedSnapshotCodec snapshotCodec,
            AuditTrailService auditTrailService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
        this.tenantHierarchyService = tenantHierarchyService;
        this.policyService = policyService;
        this.publicationService = publicationService;
        this.snapshotCodec = snapshotCodec;
        this.auditTrailService = auditTrailService;
    }

    @Transactional
    public ChangeRequest create(
            UUID environmentId,
            long expectedPublicationVersion,
            String title,
            String description) {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.ENVIRONMENT_WRITE);
        Environment environment = tenantHierarchyService.findEnvironment(environmentId);
        requireExpectedVersion(environment, expectedPublicationVersion);
        Candidate candidate = compileCandidate(environment, expectedPublicationVersion);
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO flagforge.change_requests (
                        id, organization_id, project_id, environment_id, state,
                        title, description, requester_id,
                        expected_publication_version, candidate_revision_number,
                        candidate_schema_version, candidate_algorithm_version,
                        candidate_checksum, candidate_payload, candidate_payload_size,
                        created_at)
                    VALUES (
                        :id, :organizationId, :projectId, :environmentId, 'DRAFT',
                        :title, :description, :requesterId,
                        :expectedVersion, :candidateRevisionNumber,
                        :schemaVersion, :algorithmVersion,
                        :checksum, :payload, :payloadSize, :createdAt)
                    """,
                    parameters(environment)
                            .addValue("id", id)
                            .addValue("title", requireText(title, "title", MAX_TITLE_LENGTH))
                            .addValue("description", optionalText(description, MAX_NOTE_LENGTH))
                            .addValue("requesterId", identity.actorId())
                            .addValue("expectedVersion", expectedPublicationVersion)
                            .addValue("candidateRevisionNumber", candidate.revisionNumber())
                            .addValue("schemaVersion", PublishedSnapshotCodec.SCHEMA_VERSION)
                            .addValue("algorithmVersion", PublishedSnapshotCodec.ALGORITHM_VERSION)
                            .addValue("checksum", candidate.checksum())
                            .addValue("payload", candidate.payload())
                            .addValue("payloadSize", candidate.payload().length)
                            .addValue("createdAt", Timestamp.from(now)));
        } catch (DuplicateKeyException exception) {
            throw new ChangeRequestException(
                    ChangeRequestError.ACTIVE_REQUEST_EXISTS,
                    "An active change request already exists for this environment");
        }
        append(environment, identity.actorId(), id, AuditAction.CHANGE_REQUEST_CREATED,
                Map.of("candidateChecksum", candidate.checksum()), now);
        return find(environment, id);
    }

    @Transactional
    public ChangeRequest submit(UUID environmentId, UUID changeRequestId) {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.ENVIRONMENT_WRITE);
        Environment environment = tenantHierarchyService.findEnvironment(environmentId);
        ChangeRequest request = findForUpdate(environment, changeRequestId);
        requireRequester(request, identity);
        requireState(request, ChangeRequestState.DRAFT);
        verifyCandidate(environment, request);
        Instant now = Instant.now();
        transition(environment, request, """
                UPDATE flagforge.change_requests
                SET state = 'IN_REVIEW', submitted_at = :now, version = version + 1
                WHERE organization_id = :organizationId
                  AND project_id = :projectId
                  AND environment_id = :environmentId
                  AND id = :id AND state = 'DRAFT' AND version = :version
                """, now);
        append(environment, identity.actorId(), request.id(),
                AuditAction.CHANGE_REQUEST_SUBMITTED,
                Map.of("candidateChecksum", request.candidateChecksum()), now);
        return find(environment, request.id());
    }

    @Transactional
    public ChangeRequest approve(
            UUID environmentId,
            UUID changeRequestId,
            String decisionNote) {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.CHANGE_REQUEST_REVIEW);
        Environment environment = tenantHierarchyService.findEnvironment(environmentId);
        ChangeRequest request = findForUpdate(environment, changeRequestId);
        requireState(request, ChangeRequestState.IN_REVIEW);
        if (policyService.preventSelfApproval(environment)
                && request.requesterId().equals(identity.actorId())) {
            throw new ChangeRequestException(
                    ChangeRequestError.SELF_APPROVAL_FORBIDDEN,
                    "The requester cannot approve this environment change");
        }
        verifyCandidate(environment, request);
        Instant now = Instant.now();
        int updated = jdbcTemplate.update(
                """
                UPDATE flagforge.change_requests
                SET state = 'APPROVED', reviewer_id = :reviewerId,
                    decision_note = :decisionNote, decided_at = :now,
                    version = version + 1
                WHERE organization_id = :organizationId
                  AND project_id = :projectId
                  AND environment_id = :environmentId
                  AND id = :id AND state = 'IN_REVIEW' AND version = :version
                """,
                parameters(environment)
                        .addValue("id", request.id())
                        .addValue("version", request.version())
                        .addValue("reviewerId", identity.actorId())
                        .addValue("decisionNote", optionalText(decisionNote, MAX_NOTE_LENGTH))
                        .addValue("now", Timestamp.from(now)));
        requireUpdated(updated);
        append(environment, identity.actorId(), request.id(),
                AuditAction.CHANGE_REQUEST_APPROVED,
                Map.of("candidateChecksum", request.candidateChecksum()), now);
        return find(environment, request.id());
    }

    @Transactional
    public ChangeRequest reject(
            UUID environmentId,
            UUID changeRequestId,
            String decisionNote) {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.CHANGE_REQUEST_REVIEW);
        Environment environment = tenantHierarchyService.findEnvironment(environmentId);
        ChangeRequest request = findForUpdate(environment, changeRequestId);
        requireState(request, ChangeRequestState.IN_REVIEW);
        Instant now = Instant.now();
        int updated = jdbcTemplate.update(
                """
                UPDATE flagforge.change_requests
                SET state = 'REJECTED', reviewer_id = :reviewerId,
                    decision_note = :decisionNote, decided_at = :now,
                    version = version + 1
                WHERE organization_id = :organizationId
                  AND project_id = :projectId
                  AND environment_id = :environmentId
                  AND id = :id AND state = 'IN_REVIEW' AND version = :version
                """,
                parameters(environment)
                        .addValue("id", request.id())
                        .addValue("version", request.version())
                        .addValue("reviewerId", identity.actorId())
                        .addValue("decisionNote", requireText(
                                decisionNote, "decisionNote", MAX_NOTE_LENGTH))
                        .addValue("now", Timestamp.from(now)));
        requireUpdated(updated);
        append(environment, identity.actorId(), request.id(),
                AuditAction.CHANGE_REQUEST_REJECTED, Map.of(), now);
        return find(environment, request.id());
    }

    @Transactional
    public ChangeRequest publish(UUID environmentId, UUID changeRequestId) {
        TenantIdentity identity = authorizationService.require(
                ControlPlanePermission.ENVIRONMENT_WRITE);
        Environment environment = tenantHierarchyService.findEnvironment(environmentId);
        ChangeRequest request = findForUpdate(environment, changeRequestId);
        requireState(request, ChangeRequestState.APPROVED);
        verifyCandidate(environment, request);
        PublishedRevision revision = publicationService.publish(
                environment.id(), request.expectedPublicationVersion());
        if (!MessageDigest.isEqual(
                request.candidateChecksum().getBytes(StandardCharsets.US_ASCII),
                revision.checksum().getBytes(StandardCharsets.US_ASCII))) {
            throw new ChangeRequestException(
                    ChangeRequestError.CANDIDATE_CHANGED,
                    "The published revision differs from the approved candidate");
        }
        Instant now = Instant.now();
        int updated = jdbcTemplate.update(
                """
                UPDATE flagforge.change_requests
                SET state = 'PUBLISHED', published_at = :now,
                    published_revision_id = :revisionId,
                    published_revision_number = :revisionNumber,
                    version = version + 1
                WHERE organization_id = :organizationId
                  AND project_id = :projectId
                  AND environment_id = :environmentId
                  AND id = :id AND state = 'APPROVED' AND version = :version
                """,
                parameters(environment)
                        .addValue("id", request.id())
                        .addValue("version", request.version())
                        .addValue("revisionId", revision.revisionId())
                        .addValue("revisionNumber", revision.revisionNumber())
                        .addValue("now", Timestamp.from(now)));
        requireUpdated(updated);
        append(environment, identity.actorId(), request.id(),
                AuditAction.CHANGE_REQUEST_PUBLISHED,
                Map.of(
                        "candidateChecksum", request.candidateChecksum(),
                        "revisionNumber", Long.toString(revision.revisionNumber())),
                now);
        return find(environment, request.id());
    }

    @Transactional(readOnly = true)
    public List<ChangeRequest> list(UUID environmentId) {
        authorizationService.require(ControlPlanePermission.ENVIRONMENT_READ);
        Environment environment = tenantHierarchyService.findEnvironment(environmentId);
        return jdbcTemplate.query(
                selectSql() + " ORDER BY created_at DESC, id DESC",
                parameters(environment),
                (rs, rowNumber) -> map(rs));
    }

    @Transactional(readOnly = true)
    public ChangeRequest get(UUID environmentId, UUID changeRequestId) {
        authorizationService.require(ControlPlanePermission.ENVIRONMENT_READ);
        return find(tenantHierarchyService.findEnvironment(environmentId), changeRequestId);
    }

    @Transactional(readOnly = true)
    public CandidateDiff diff(UUID environmentId, UUID changeRequestId) {
        authorizationService.require(ControlPlanePermission.ENVIRONMENT_READ);
        Environment environment = tenantHierarchyService.findEnvironment(environmentId);
        ChangeRequest request = find(environment, changeRequestId);
        byte[] candidatePayload = jdbcTemplate.queryForObject(
                """
                SELECT candidate_payload
                FROM flagforge.change_requests
                WHERE organization_id = :organizationId
                  AND project_id = :projectId
                  AND environment_id = :environmentId
                  AND id = :id
                """,
                parameters(environment).addValue("id", changeRequestId),
                byte[].class);
        Baseline baseline = loadBaseline(environment);
        Map<String, String> before = baseline == null
                ? Map.of()
                : flatten(baseline.snapshot());
        PublishedSnapshot candidateSnapshot = snapshotCodec.decode(
                candidatePayload,
                request.candidateChecksum());
        Map<String, String> after = flatten(candidateSnapshot);
        TreeSet<String> paths = new TreeSet<>();
        paths.addAll(before.keySet());
        paths.addAll(after.keySet());
        List<CandidateDifference> differences = new ArrayList<>();
        for (String path : paths) {
            String beforeValue = before.get(path);
            String afterValue = after.get(path);
            if (!Objects.equals(beforeValue, afterValue)) {
                differences.add(new CandidateDifference(
                        path,
                        beforeValue == null ? DifferenceType.ADDED
                                : afterValue == null ? DifferenceType.REMOVED
                                : DifferenceType.CHANGED,
                        beforeValue,
                        afterValue));
            }
        }
        boolean valid;
        try {
            verifyCandidate(environment, request);
            valid = true;
        } catch (ChangeRequestException | PublicationException exception) {
            valid = false;
        }
        return new CandidateDiff(
                request.id(),
                baseline == null ? null : baseline.revisionNumber(),
                request.candidateRevisionNumber(),
                request.candidateChecksum(),
                valid,
                differences);
    }

    public void requireDirectPublicationAllowed(UUID environmentId) {
        requireDirectPublicationAllowed(
                tenantHierarchyService.findEnvironment(environmentId));
    }

    public void requireDirectPublicationAllowed(Environment environment) {
        if (policyService.approvalRequired(environment)) {
            throw new ChangeRequestException(
                    ChangeRequestError.APPROVAL_REQUIRED,
                    "Direct publication is disabled for this environment");
        }
    }

    private void verifyCandidate(Environment environment, ChangeRequest request) {
        requireExpectedVersion(environment, request.expectedPublicationVersion());
        Candidate current = compileCandidate(
                environment, request.expectedPublicationVersion());
        if (!MessageDigest.isEqual(
                request.candidateChecksum().getBytes(StandardCharsets.US_ASCII),
                current.checksum().getBytes(StandardCharsets.US_ASCII))) {
            throw new ChangeRequestException(
                    ChangeRequestError.CANDIDATE_CHANGED,
                    "The draft changed after this change request was created");
        }
    }

    private Candidate compileCandidate(Environment environment, long expectedVersion) {
        PreparedPublication prepared = publicationService.prepare(
                environment.id(),
                expectedVersion);
        return new Candidate(
                prepared.revisionNumber(),
                prepared.checksum(),
                prepared.payload());
    }

    private Baseline loadBaseline(Environment environment) {
        return jdbcTemplate.query(
                        """
                        SELECT revision.revision_number, snapshot.payload, snapshot.checksum
                        FROM flagforge.environment_publication_state state
                        JOIN flagforge.configuration_revisions revision
                          ON revision.organization_id = state.organization_id
                         AND revision.project_id = state.project_id
                         AND revision.environment_id = state.environment_id
                         AND revision.id = state.current_revision_id
                        JOIN flagforge.configuration_snapshots snapshot
                          ON snapshot.organization_id = revision.organization_id
                         AND snapshot.project_id = revision.project_id
                         AND snapshot.environment_id = revision.environment_id
                         AND snapshot.revision_id = revision.id
                        WHERE state.organization_id = :organizationId
                          AND state.project_id = :projectId
                          AND state.environment_id = :environmentId
                        """,
                        parameters(environment),
                        (rs, rowNumber) -> new Baseline(
                                rs.getLong("revision_number"),
                                snapshotCodec.decode(
                                        rs.getBytes("payload"),
                                        rs.getString("checksum"))))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static Map<String, String> flatten(PublishedSnapshot snapshot) {
        Map<String, String> values = new TreeMap<>();
        for (PublishedFlag flag : snapshot.flags()) {
            String path = "flags." + flag.key();
            values.put(path + ".type", flag.valueType().name());
            values.put(path + ".enabled", Boolean.toString(flag.enabled()));
            values.put(path + ".defaultVariant", flag.defaultVariant());
            for (PublishedVariant variant : flag.variants()) {
                String value = variant.booleanValue() == null
                        ? variant.stringValue()
                        : variant.booleanValue().toString();
                values.put(
                        path + ".variants." + variant.key(),
                        variant.valueType().name() + ":" + value);
            }
        }
        for (var target : snapshot.targetingConfiguration().flags()) {
            String path = "targeting.flags." + target.key();
            values.put(path + ".defaultVariant", target.defaultVariant());
            values.put(
                    path + ".variants",
                    String.join(",", new TreeSet<>(target.variants())));
            for (int index = 0; index < target.prerequisites().size(); index++) {
                var prerequisite = target.prerequisites().get(index);
                values.put(
                        path + ".prerequisites." + index,
                        prerequisite.flagKey() + "="
                                + prerequisite.expectedVariant());
            }
            for (var rule : target.rules()) {
                String rulePath = path + ".rules." + rule.priority()
                        + "." + rule.key();
                values.put(rulePath + ".variant", rule.variantKey());
                for (int index = 0; index < rule.conditions().size(); index++) {
                    values.put(
                            rulePath + ".conditions." + index,
                            rule.conditions().get(index).toString());
                }
            }
        }
        for (var segment : snapshot.targetingConfiguration().segments()) {
            String path = "targeting.segments." + segment.key();
            values.put(
                    path + ".includedTargetingKeys",
                    String.join(",", new TreeSet<>(
                            segment.includedTargetingKeys())));
            values.put(
                    path + ".excludedTargetingKeys",
                    String.join(",", new TreeSet<>(
                            segment.excludedTargetingKeys())));
            for (int index = 0; index < segment.conditions().size(); index++) {
                values.put(
                        path + ".conditions." + index,
                        segment.conditions().get(index).toString());
            }
        }
        return values;
    }

    private void requireExpectedVersion(Environment environment, long expectedVersion) {
        Long current = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE((
                    SELECT pointer_version
                    FROM flagforge.environment_publication_state
                    WHERE organization_id = :organizationId
                      AND project_id = :projectId
                      AND environment_id = :environmentId
                ), 0)
                """,
                parameters(environment),
                Long.class);
        if (expectedVersion < 0 || current == null || current != expectedVersion) {
            throw new ChangeRequestException(
                    ChangeRequestError.VERSION_CONFLICT,
                    "The environment publication version changed");
        }
    }

    private ChangeRequest findForUpdate(Environment environment, UUID id) {
        return jdbcTemplate.query(
                        selectSql() + " AND id = :id FOR UPDATE",
                        parameters(environment).addValue("id", id),
                        (rs, rowNumber) -> map(rs))
                .stream()
                .findFirst()
                .orElseThrow(TenantAccessException::resourceNotFound);
    }

    private ChangeRequest find(Environment environment, UUID id) {
        return jdbcTemplate.query(
                        selectSql() + " AND id = :id",
                        parameters(environment).addValue("id", id),
                        (rs, rowNumber) -> map(rs))
                .stream()
                .findFirst()
                .orElseThrow(TenantAccessException::resourceNotFound);
    }

    private static String selectSql() {
        return """
                SELECT id, state, title, description, requester_id, reviewer_id,
                       decision_note, expected_publication_version,
                       candidate_revision_number, candidate_schema_version,
                       candidate_algorithm_version, candidate_checksum,
                       candidate_payload_size, created_at, submitted_at,
                       decided_at, published_at, published_revision_id,
                       published_revision_number, version
                FROM flagforge.change_requests
                WHERE organization_id = :organizationId
                  AND project_id = :projectId
                  AND environment_id = :environmentId
                """;
    }

    private void transition(
            Environment environment,
            ChangeRequest request,
            String sql,
            Instant now) {
        int updated = jdbcTemplate.update(
                sql,
                parameters(environment)
                        .addValue("id", request.id())
                        .addValue("version", request.version())
                        .addValue("now", Timestamp.from(now)));
        requireUpdated(updated);
    }

    private void append(
            Environment environment,
            String actorId,
            UUID requestId,
            AuditAction action,
            Map<String, String> details,
            Instant now) {
        auditTrailService.append(new AuditCommand(
                environment.organizationId(), environment.projectId(), environment.id(),
                actorId, action, AuditResourceType.CHANGE_REQUEST, requestId,
                null, null, details, now));
    }

    private static ChangeRequest map(ResultSet rs) throws SQLException {
        return new ChangeRequest(
                rs.getObject("id", UUID.class),
                ChangeRequestState.valueOf(rs.getString("state")),
                rs.getString("title"), rs.getString("description"),
                rs.getString("requester_id"), rs.getString("reviewer_id"),
                rs.getString("decision_note"),
                rs.getLong("expected_publication_version"),
                rs.getLong("candidate_revision_number"),
                rs.getInt("candidate_schema_version"),
                rs.getString("candidate_algorithm_version"),
                rs.getString("candidate_checksum"),
                rs.getInt("candidate_payload_size"),
                instant(rs, "created_at"), instant(rs, "submitted_at"),
                instant(rs, "decided_at"), instant(rs, "published_at"),
                rs.getObject("published_revision_id", UUID.class),
                nullableLong(rs, "published_revision_number"),
                rs.getLong("version"));
    }

    private static MapSqlParameterSource parameters(Environment environment) {
        return new MapSqlParameterSource()
                .addValue("organizationId", environment.organizationId())
                .addValue("projectId", environment.projectId())
                .addValue("environmentId", environment.id());
    }

    private static void requireRequester(ChangeRequest request, TenantIdentity identity) {
        if (!request.requesterId().equals(identity.actorId())) {
            throw new ChangeRequestException(
                    ChangeRequestError.INVALID_TRANSITION,
                    "Only the requester can submit this change request");
        }
    }

    private static void requireState(
            ChangeRequest request,
            ChangeRequestState required) {
        if (request.state() != required) {
            throw new ChangeRequestException(
                    ChangeRequestError.INVALID_TRANSITION,
                    "Change request state does not allow this transition");
        }
    }

    private static void requireUpdated(int updated) {
        if (updated != 1) {
            throw new ChangeRequestException(
                    ChangeRequestError.VERSION_CONFLICT,
                    "The change request was modified concurrently");
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new ChangeRequestException(
                    ChangeRequestError.INVALID_REQUEST,
                    field + " is invalid");
        }
        return value.strip();
    }

    private static String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > maxLength) {
            throw new ChangeRequestException(
                    ChangeRequestError.INVALID_REQUEST,
                    "Text exceeds the maximum length");
        }
        return value.strip();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record Baseline(
            long revisionNumber,
            PublishedSnapshot snapshot) {
    }

    private record Candidate(long revisionNumber, String checksum, byte[] payload) {
        private Candidate {
            payload = payload.clone();
        }
        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    public enum DifferenceType {
        ADDED,
        REMOVED,
        CHANGED
    }

    public record CandidateDifference(
            String path,
            DifferenceType type,
            String beforeValue,
            String afterValue) {
    }

    public record CandidateDiff(
            UUID changeRequestId,
            Long fromRevision,
            long candidateRevision,
            String candidateChecksum,
            boolean candidateValid,
            List<CandidateDifference> differences) {

        public CandidateDiff {
            differences = List.copyOf(Objects.requireNonNull(
                    differences,
                    "differences are required"));
        }
    }

    public enum ChangeRequestState {
        DRAFT,
        IN_REVIEW,
        APPROVED,
        PUBLISHED,
        REJECTED
    }

    public enum ChangeRequestError {
        INVALID_REQUEST,
        INVALID_TRANSITION,
        ACTIVE_REQUEST_EXISTS,
        SELF_APPROVAL_FORBIDDEN,
        CANDIDATE_CHANGED,
        APPROVAL_REQUIRED,
        VERSION_CONFLICT
    }

    public static final class ChangeRequestException extends RuntimeException {
        private final ChangeRequestError code;

        public ChangeRequestException(ChangeRequestError code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code is required");
        }

        public ChangeRequestError code() {
            return code;
        }
    }

    public record ChangeRequest(
            UUID id,
            ChangeRequestState state,
            String title,
            String description,
            String requesterId,
            String reviewerId,
            String decisionNote,
            long expectedPublicationVersion,
            long candidateRevisionNumber,
            int candidateSchemaVersion,
            String candidateAlgorithmVersion,
            String candidateChecksum,
            int candidatePayloadSize,
            Instant createdAt,
            Instant submittedAt,
            Instant decidedAt,
            Instant publishedAt,
            UUID publishedRevisionId,
            Long publishedRevisionNumber,
            long version) {
    }
}
