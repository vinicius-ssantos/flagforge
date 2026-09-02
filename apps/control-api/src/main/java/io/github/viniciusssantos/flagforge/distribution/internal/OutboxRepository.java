package io.github.viniciusssantos.flagforge.distribution.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.distribution.PublishedConfigurationEvent;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

@Repository
public class OutboxRepository {

    private static final String PUBLISHED_EVENT = "CONFIGURATION_PUBLISHED";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    OutboxRepository(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void insertPending(PublishedConfigurationEvent event, Timestamp occurredAt) {
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
                    :id,
                    :organizationId,
                    :projectId,
                    :environmentId,
                    :revisionId,
                    :revisionNumber,
                    :eventType,
                    :checksum,
                    CAST(:payload AS jsonb),
                    'PENDING',
                    0,
                    :occurredAt,
                    :occurredAt
                )
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("id", event.eventId())
                .addValue("organizationId", event.organizationId())
                .addValue("projectId", event.projectId())
                .addValue("environmentId", event.environmentId())
                .addValue("revisionId", event.revisionId())
                .addValue("revisionNumber", event.revisionNumber())
                .addValue("eventType", PUBLISHED_EVENT)
                .addValue("checksum", event.checksum())
                .addValue("payload", payload(event))
                .addValue("occurredAt", occurredAt));
    }

    /**
     * Takes the next deliverable events and locks them for this worker.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes more than one relay safe: a row already
     * claimed by another worker is skipped rather than waited on, so replicas share the backlog
     * instead of blocking each other or delivering the same event twice.
     *
     * <p>Ordering by {@code occurred_at} keeps delivery close to publication order, but consumers
     * still must not depend on it — a retried event legitimately arrives after a newer one.
     */
    public List<OutboxRecord> claimDeliverable(Instant now, int batchSize) {
        String sql = """
                SELECT
                    id,
                    organization_id,
                    project_id,
                    environment_id,
                    revision_id,
                    revision_number,
                    checksum,
                    occurred_at,
                    delivery_attempts
                FROM flagforge.configuration_outbox
                WHERE status = 'PENDING'
                  AND available_at <= :now
                ORDER BY occurred_at, revision_number
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("now", Timestamp.from(now))
                        .addValue("batchSize", batchSize),
                (rs, rowNumber) -> new OutboxRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("environment_id", UUID.class),
                        rs.getObject("revision_id", UUID.class),
                        rs.getLong("revision_number"),
                        rs.getString("checksum"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getInt("delivery_attempts")));
    }

    public void markDelivered(UUID eventId, int attempts) {
        update(eventId, "DELIVERED", attempts, null);
    }

    public void markFailed(UUID eventId, int attempts) {
        update(eventId, "FAILED", attempts, null);
    }

    public void reschedule(UUID eventId, int attempts, Instant availableAt) {
        update(eventId, "PENDING", attempts, Timestamp.from(availableAt));
    }

    public long countPending() {
        String sql = """
                SELECT COUNT(*) FROM flagforge.configuration_outbox WHERE status = 'PENDING'
                """;
        Long pending = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), Long.class);
        return pending == null ? 0L : pending;
    }

    private void update(UUID eventId, String status, int attempts, Timestamp availableAt) {
        String sql = """
                UPDATE flagforge.configuration_outbox
                SET status = :status,
                    delivery_attempts = :attempts,
                    available_at = COALESCE(:availableAt, available_at)
                WHERE id = :id
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("id", eventId)
                .addValue("status", status)
                .addValue("attempts", attempts)
                .addValue("availableAt", availableAt));
    }

    private String payload(PublishedConfigurationEvent event) {
        return objectMapper.writeValueAsString(new OutboxPayload(
                PUBLISHED_EVENT,
                event.revisionId(),
                event.organizationId(),
                event.projectId(),
                event.environmentId(),
                event.revisionNumber(),
                event.checksum(),
                event.occurredAt()));
    }

    private record OutboxPayload(
            String eventType,
            UUID revisionId,
            UUID organizationId,
            UUID projectId,
            UUID environmentId,
            long revisionNumber,
            String checksum,
            Instant publishedAt) {
    }
}
