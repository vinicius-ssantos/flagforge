package io.github.viniciusssantos.flagforge.distribution.internal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.viniciusssantos.flagforge.distribution.OutboxRelay;
import io.github.viniciusssantos.flagforge.distribution.OutboxRelayProperties;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Decides when the relay runs.
 *
 * <p>Two triggers with different jobs. The post-commit trigger fires as soon as a publication
 * commits, so propagation does not wait for a poll. The poll is the safety net: it recovers events
 * whose trigger was lost to a crash or a restart, which makes the trigger an optimisation rather
 * than something correctness depends on.
 *
 * <p>Passes run on one thread so two never overlap, and a trigger arriving while a pass is queued
 * is collapsed into it — a burst of publications causes one pass, not one pass each.
 */
@Component
@ConditionalOnProperty(
        prefix = "flagforge.distribution.relay",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
class OutboxRelayScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay outboxRelay;
    private final OutboxRelayProperties properties;
    private final ExecutorService executor;
    private final AtomicBoolean passQueued = new AtomicBoolean();

    OutboxRelayScheduler(OutboxRelay outboxRelay, OutboxRelayProperties properties) {
        this.outboxRelay = outboxRelay;
        this.properties = properties;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "flagforge-outbox-relay");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(fixedDelayString = "${flagforge.distribution.relay.poll-interval:5s}")
    void poll() {
        trigger();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onConfigurationEventRecorded(ConfigurationEventRecorded event) {
        trigger();
    }

    private void trigger() {
        if (!passQueued.compareAndSet(false, true)) {
            return;
        }
        executor.execute(this::runPass);
    }

    /**
     * Drains the backlog, then stops.
     *
     * <p>The queued flag is cleared before work begins so an event recorded during the pass queues
     * another one rather than being missed. Draining continues only while a pass fills its batch,
     * which is what stops a permanently failing event from spinning: rescheduled events are no
     * longer claimable, so the next pass returns fewer.
     */
    private void runPass() {
        passQueued.set(false);
        try {
            int delivered;
            do {
                delivered = outboxRelay.deliverPending();
            } while (delivered >= properties.batchSize());
        } catch (RuntimeException exception) {
            LOGGER.error("Outbox relay pass failed", exception);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
