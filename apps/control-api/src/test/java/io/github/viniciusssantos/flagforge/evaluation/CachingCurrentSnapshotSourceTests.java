package io.github.viniciusssantos.flagforge.evaluation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.viniciusssantos.flagforge.distribution.PublishedConfigurationEvent;
import io.github.viniciusssantos.flagforge.evaluation.CurrentSnapshotSource.CurrentSnapshot;
import io.github.viniciusssantos.flagforge.evaluation.CurrentSnapshotSource.SnapshotIdentity;
import io.github.viniciusssantos.flagforge.publishing.PublishedSnapshotCodec.PublishedSnapshot;
import io.github.viniciusssantos.flagforge.targeting.TargetingEngine.TargetingConfiguration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the caching policy without a database.
 *
 * <p>The delegate is a counting stub, so "went to the source of truth" is directly observable
 * rather than inferred from timing, and the clock is moved rather than waited on.
 */
class CachingCurrentSnapshotSourceTests {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID ENVIRONMENT = UUID.randomUUID();

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-02T12:00:00Z"));
    private final CountingSource delegate = new CountingSource();

    @Test
    void readsTheSourceOfTruthOnceAndThenAnswersFromMemory() {
        CachingCurrentSnapshotSource cache = cache(properties(true));

        assertThat(cache.loadCurrent(ORGANIZATION, ENVIRONMENT)).isPresent();
        assertThat(cache.loadCurrent(ORGANIZATION, ENVIRONMENT)).isPresent();

        assertThat(delegate.calls()).isEqualTo(1);
    }

    @Test
    void confirmsThePointerAgainAfterItsTimeToLive() {
        CachingCurrentSnapshotSource cache = cache(properties(true));
        cache.loadCurrent(ORGANIZATION, ENVIRONMENT);

        clock.advance(Duration.ofSeconds(31));
        cache.loadCurrent(ORGANIZATION, ENVIRONMENT);

        assertThat(delegate.calls()).isEqualTo(2);
    }

    @Test
    void publicationInvalidatesThePointerSoTheNextReadSeesTheNewVersion() {
        CachingCurrentSnapshotSource cache = cache(properties(true));
        cache.loadCurrent(ORGANIZATION, ENVIRONMENT);
        delegate.publishRevision(2);

        cache.onConfigurationPublished(event(2));
        Optional<CurrentSnapshot> refreshed = cache.loadCurrent(ORGANIZATION, ENVIRONMENT);

        assertThat(delegate.calls()).isEqualTo(2);
        assertThat(refreshed).get().extracting(CurrentSnapshot::revisionNumber).isEqualTo(2L);
    }

    @Test
    void anOlderEventDoesNotInvalidateANewerPointer() {
        CachingCurrentSnapshotSource cache = cache(properties(true));
        delegate.publishRevision(5);
        cache.loadCurrent(ORGANIZATION, ENVIRONMENT);

        // Delivery is at least once and unordered; a late event for an old version means nothing.
        cache.onConfigurationPublished(event(3));
        cache.loadCurrent(ORGANIZATION, ENVIRONMENT);

        assertThat(delegate.calls()).isEqualTo(1);
    }

    @Test
    void concurrentMissesForTheSameVersionLoadItOnce() throws Exception {
        CachingCurrentSnapshotSource cache = cache(properties(true));
        int workers = 8;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            Callable<Optional<CurrentSnapshot>> attempt = () -> {
                ready.countDown();
                start.await();
                return cache.loadCurrent(ORGANIZATION, ENVIRONMENT);
            };
            List<Future<Optional<CurrentSnapshot>>> futures = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(attempt));
            }
            ready.await();
            start.countDown();
            for (Future<Optional<CurrentSnapshot>> future : futures) {
                assertThat(future.get()).isPresent();
            }
        } finally {
            executor.shutdownNow();
        }

        // Every worker resolves the same identity, so the decode is shared rather than repeated.
        assertThat(delegate.decodes()).isEqualTo(1);
    }

    @Test
    void servesTheLastKnownGoodDocumentWhileTheDatabaseIsUnreachable() {
        CachingCurrentSnapshotSource cache = cache(properties(true));
        cache.loadCurrent(ORGANIZATION, ENVIRONMENT);

        delegate.failWith(new DataRetrievalFailureException("database unavailable"));
        clock.advance(Duration.ofSeconds(31));

        Optional<CurrentSnapshot> served = cache.loadCurrent(ORGANIZATION, ENVIRONMENT);

        assertThat(served).isPresent();
        assertThat(served.get().stale()).isTrue();
        assertThat(served.get().revisionNumber()).isEqualTo(1);
    }

    @Test
    void stopsServingOnceTheStalenessBudgetIsSpent() {
        CachingCurrentSnapshotSource cache = cache(properties(true));
        cache.loadCurrent(ORGANIZATION, ENVIRONMENT);

        delegate.failWith(new DataRetrievalFailureException("database unavailable"));
        clock.advance(Duration.ofMinutes(6));

        assertThatThrownBy(() -> cache.loadCurrent(ORGANIZATION, ENVIRONMENT))
                .isInstanceOf(DataRetrievalFailureException.class);
    }

    @Test
    void neverAnswersFromMemoryWhenDisabled() {
        CachingCurrentSnapshotSource cache = cache(properties(false));

        cache.loadCurrent(ORGANIZATION, ENVIRONMENT);
        cache.loadCurrent(ORGANIZATION, ENVIRONMENT);

        assertThat(delegate.calls()).isEqualTo(2);
    }

    @Test
    void keepsEnvironmentsApartEvenAtTheSameRevisionNumber() {
        CachingCurrentSnapshotSource cache = cache(properties(true));
        UUID otherEnvironment = UUID.randomUUID();

        cache.loadCurrent(ORGANIZATION, ENVIRONMENT);
        Optional<CurrentSnapshot> other = cache.loadCurrent(ORGANIZATION, otherEnvironment);

        assertThat(delegate.calls()).isEqualTo(2);
        assertThat(other).get().extracting(CurrentSnapshot::checksum).isNotNull();
    }

    private CachingCurrentSnapshotSource cache(EvaluationCacheProperties properties) {
        return new CachingCurrentSnapshotSource(
                delegate,
                properties,
                new EvaluationCacheMetrics(new SimpleMeterRegistry()),
                clock);
    }

    private static EvaluationCacheProperties properties(boolean enabled) {
        return new EvaluationCacheProperties(
                enabled,
                256,
                Duration.ofSeconds(30),
                Duration.ofMinutes(5));
    }

    private static PublishedConfigurationEvent event(long revisionNumber) {
        return new PublishedConfigurationEvent(
                UUID.randomUUID(),
                ORGANIZATION,
                PROJECT,
                ENVIRONMENT,
                UUID.randomUUID(),
                revisionNumber,
                "a".repeat(64),
                Instant.parse("2026-09-02T12:00:00Z"));
    }

    /**
     * Stands in for the database, counting reads so cache hits are observable.
     */
    private static final class CountingSource extends DatabaseCurrentSnapshotSource {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger decodes = new AtomicInteger();
        private volatile long revisionNumber = 1;
        private volatile RuntimeException failure;

        private CountingSource() {
            super(null, null);
        }

        @Override
        public Optional<SnapshotIdentity> loadCurrentIdentity(UUID organizationId, UUID environmentId) {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return Optional.of(new SnapshotIdentity(
                    PROJECT,
                    UUID.nameUUIDFromBytes(("revision-" + revisionNumber).getBytes()),
                    revisionNumber,
                    2,
                    "flagforge-rollout-v1",
                    checksumFor(revisionNumber)));
        }

        @Override
        public CurrentSnapshot loadDocument(
                UUID organizationId,
                UUID environmentId,
                SnapshotIdentity identity) {
            decodes.incrementAndGet();
            return new CurrentSnapshot(
                    identity.projectId(),
                    identity.revisionId(),
                    identity.revisionNumber(),
                    identity.schemaVersion(),
                    identity.algorithmVersion(),
                    identity.checksum(),
                    document(environmentId, identity.revisionNumber()),
                    false);
        }

        void publishRevision(long revisionNumber) {
            this.revisionNumber = revisionNumber;
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        int calls() {
            return calls.get();
        }

        int decodes() {
            return decodes.get();
        }

        private static String checksumFor(long revisionNumber) {
            return String.format("%064d", revisionNumber);
        }

        private static PublishedSnapshot document(UUID environmentId, long revisionNumber) {
            return new PublishedSnapshot(
                    2,
                    ORGANIZATION,
                    PROJECT,
                    environmentId,
                    revisionNumber,
                    "flagforge-rollout-v1",
                    List.of(),
                    new TargetingConfiguration(List.of(), List.of()));
        }
    }

    private static final class MutableClock extends Clock {

        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
