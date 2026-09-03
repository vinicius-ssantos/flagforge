package io.github.viniciusssantos.flagforge.evaluation;

import java.util.Optional;
import java.util.UUID;

import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedSnapshot;

/**
 * Resolves the configuration currently effective for one environment.
 *
 * <p>Separated from {@link EvaluationSnapshotProvider} because the unit that can be cached is the
 * whole published document, not one flag's view of it. Every flag in an environment shares the same
 * targeting graph, so caching per flag would keep one copy of that graph per flag.
 */
interface CurrentSnapshotSource {

    Optional<CurrentSnapshot> loadCurrent(UUID organizationId, UUID environmentId);

    /**
     * Which version an environment currently points at.
     *
     * <p>Kept separate from loading the document because the pointer changes and the document it
     * names never does. Resolving them independently is what lets a decoded document survive a
     * pointer refresh that confirms the same version.
     */
    record SnapshotIdentity(
            UUID projectId,
            UUID revisionId,
            long revisionNumber,
            int schemaVersion,
            String algorithmVersion,
            String checksum) {
    }

    /**
     * A decoded document together with the identity it was published under.
     *
     * @param stale whether this was served from a last-known-good copy rather than the source of
     *              truth. It travels with the document so the reason reaches the evaluation
     *              response instead of being inferred somewhere further down.
     */
    record CurrentSnapshot(
            UUID projectId,
            UUID revisionId,
            long revisionNumber,
            int schemaVersion,
            String algorithmVersion,
            String checksum,
            PublishedSnapshot document,
            boolean stale) {

        CurrentSnapshot asStale() {
            return new CurrentSnapshot(
                    projectId,
                    revisionId,
                    revisionNumber,
                    schemaVersion,
                    algorithmVersion,
                    checksum,
                    document,
                    true);
        }
    }
}
