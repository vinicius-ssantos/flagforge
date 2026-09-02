package io.github.viniciusssantos.flagforge.distribution;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A configuration version that became effective for one environment.
 *
 * <p>Carries everything a consumer needs to act without reading the database: the tenant it belongs
 * to, the environment whose pointer moved, the revision number that identifies the version, the
 * checksum of the exact payload, and when it was published. A rollback publishes this same event,
 * because to an evaluator a rollback is simply a new effective version.
 */
public record PublishedConfigurationEvent(
        UUID eventId,
        UUID organizationId,
        UUID projectId,
        UUID environmentId,
        UUID revisionId,
        long revisionNumber,
        String checksum,
        Instant occurredAt) {

    public PublishedConfigurationEvent {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(organizationId, "organizationId is required");
        Objects.requireNonNull(projectId, "projectId is required");
        Objects.requireNonNull(environmentId, "environmentId is required");
        Objects.requireNonNull(revisionId, "revisionId is required");
        Objects.requireNonNull(checksum, "checksum is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        if (revisionNumber < 1) {
            throw new IllegalArgumentException("revisionNumber must be positive");
        }
    }
}
