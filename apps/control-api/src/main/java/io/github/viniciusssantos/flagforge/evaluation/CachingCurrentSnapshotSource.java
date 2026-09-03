package io.github.viniciusssantos.flagforge.evaluation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.viniciusssantos.flagforge.distribution.ConfigurationChangeListener;
import io.github.viniciusssantos.flagforge.distribution.ConfigurationEventRecorded;
import io.github.viniciusssantos.flagforge.distribution.PublishedConfigurationEvent;

import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Serves the effective configuration from memory, falling back to the source of truth.
 *
 * <p>Two caches, because two different things are being remembered. A published document is
 * immutable: once decoded under a given identity it never needs invalidating, only evicting. What
 * changes is which version is current, and that pointer is what publication invalidates.
 *
 * <p>Both layers go through {@code Caffeine.get(key, loader)}, whose {@code computeIfAbsent}
 * semantics run one loader per key. That is what keeps a burst of concurrent misses from becoming a
 * burst of database reads — the stampede protection is the cache's, not something layered on top.
 *
 * <p>PostgreSQL remains the source of truth. A cached document only ever answers for the version
 * the pointer names, and the pointer is re-confirmed whenever it expires or is invalidated.
 */
@Component
@Primary
class CachingCurrentSnapshotSource implements CurrentSnapshotSource, ConfigurationChangeListener {

    private final DatabaseCurrentSnapshotSource delegate;
    private final EvaluationCacheProperties properties;
    private final EvaluationCacheMetrics metrics;
    private final Clock clock;
    private final Cache<PointerKey, SnapshotIdentity> pointers;
    private final Cache<SnapshotKey, CurrentSnapshot> snapshots;
    private final ConcurrentMap<PointerKey, LastKnownGood> lastKnownGood = new ConcurrentHashMap<>();

    CachingCurrentSnapshotSource(
            DatabaseCurrentSnapshotSource delegate,
            EvaluationCacheProperties properties,
            EvaluationCacheMetrics metrics,
            Clock clock) {
        this.delegate = delegate;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.pointers = Caffeine.newBuilder()
                .expireAfterWrite(properties.pointerTtl())
                .ticker(() -> clock.instant().toEpochMilli() * 1_000_000L)
                .build();
        this.snapshots = Caffeine.newBuilder()
                .maximumSize(properties.maximumSnapshots())
                .build();
    }

    @Override
    public Optional<CurrentSnapshot> loadCurrent(UUID organizationId, UUID environmentId) {
        if (!properties.enabled()) {
            return delegate.loadCurrent(organizationId, environmentId);
        }

        PointerKey pointerKey = new PointerKey(organizationId, environmentId);
        try {
            return Optional.ofNullable(resolve(pointerKey));
        } catch (DataAccessException exception) {
            Optional<CurrentSnapshot> served = lastKnownGood(pointerKey);
            if (served.isEmpty()) {
                throw exception;
            }
            return served;
        }
    }

    private CurrentSnapshot resolve(PointerKey pointerKey) {
        boolean[] pointerLoaded = {false};
        SnapshotIdentity identity = pointers.get(pointerKey, key -> {
            pointerLoaded[0] = true;
            return delegate.loadCurrentIdentity(key.organizationId(), key.environmentId())
                    .orElse(null);
        });
        if (pointerLoaded[0]) {
            metrics.pointerMiss();
        } else {
            metrics.pointerHit();
        }
        if (identity == null) {
            return null;
        }

        SnapshotKey snapshotKey = SnapshotKey.of(pointerKey, identity);
        boolean[] documentLoaded = {false};
        CurrentSnapshot snapshot = snapshots.get(snapshotKey, key -> {
            documentLoaded[0] = true;
            return delegate.loadDocument(
                    pointerKey.organizationId(),
                    pointerKey.environmentId(),
                    identity);
        });
        if (documentLoaded[0]) {
            metrics.snapshotMiss();
        } else {
            metrics.snapshotHit();
        }

        lastKnownGood.put(
                pointerKey,
                new LastKnownGood(snapshotKey, identity.revisionNumber(), Instant.now(clock)));
        return snapshot;
    }

    /**
     * Answers from the newest document still held, while it is young enough to trust.
     *
     * <p>Reached only when the source of truth is unreachable. Past the budget the failure is
     * surfaced instead: an answer nobody can put a date on is worse than an explicit error.
     */
    private Optional<CurrentSnapshot> lastKnownGood(PointerKey pointerKey) {
        LastKnownGood held = lastKnownGood.get(pointerKey);
        if (held == null || !held.isTrustedAt(Instant.now(clock), properties.stalenessBudget())) {
            return Optional.empty();
        }
        CurrentSnapshot cached = snapshots.getIfPresent(held.snapshotKey());
        if (cached == null) {
            return Optional.empty();
        }
        metrics.servedStale();
        return Optional.of(cached.asStale());
    }

    /**
     * Drops the pointer so the next evaluation confirms it.
     *
     * <p>Delivery is at least once and may arrive out of order, so an event is ignored unless it
     * names a version newer than the one held. The document cache is untouched: entries there are
     * immutable and keyed by version, so an old one is never wrong, only unused.
     */
    @Override
    public void onConfigurationPublished(PublishedConfigurationEvent event) {
        invalidateIfNewer(event);
    }

    /**
     * Invalidates as soon as a local publication commits.
     *
     * <p>The relay's job is telling other nodes. The node that published already knows, and making
     * it wait for its own asynchronous delivery would leave a window where an operator publishes and
     * then reads back the previous version from the very instance that accepted the change.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onLocalPublication(ConfigurationEventRecorded recorded) {
        invalidateIfNewer(recorded.event());
    }

    private void invalidateIfNewer(PublishedConfigurationEvent event) {
        PointerKey pointerKey = new PointerKey(event.organizationId(), event.environmentId());
        LastKnownGood held = lastKnownGood.get(pointerKey);
        if (held != null && event.revisionNumber() <= held.revisionNumber()) {
            return;
        }
        pointers.invalidate(pointerKey);
    }

    private record PointerKey(UUID organizationId, UUID environmentId) {
    }

    /**
     * Full identity of a published document.
     *
     * <p>Includes the checksum so a revision number can never address a different payload, and the
     * environment so two tenants at the same revision number stay separate.
     */
    private record SnapshotKey(
            UUID organizationId,
            UUID projectId,
            UUID environmentId,
            long revisionNumber,
            String checksum) {

        static SnapshotKey of(PointerKey pointerKey, SnapshotIdentity identity) {
            return new SnapshotKey(
                    pointerKey.organizationId(),
                    identity.projectId(),
                    pointerKey.environmentId(),
                    identity.revisionNumber(),
                    identity.checksum());
        }
    }

    private record LastKnownGood(SnapshotKey snapshotKey, long revisionNumber, Instant confirmedAt) {

        boolean isTrustedAt(Instant now, Duration budget) {
            return !now.isAfter(confirmedAt.plus(budget));
        }
    }
}
