package io.github.viniciusssantos.flagforge.distribution.internal;

import java.time.Instant;
import java.util.UUID;

/**
 * One claimed outbox row.
 *
 * <p>Built from the typed columns rather than the stored JSON payload: the columns are what the
 * schema constrains and indexes, so they are the authoritative shape.
 */
public record OutboxRecord(
        UUID eventId,
        UUID organizationId,
        UUID projectId,
        UUID environmentId,
        UUID revisionId,
        long revisionNumber,
        String checksum,
        Instant occurredAt,
        int deliveryAttempts) {
}
