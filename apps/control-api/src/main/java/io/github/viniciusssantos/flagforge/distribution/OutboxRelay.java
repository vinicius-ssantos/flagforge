package io.github.viniciusssantos.flagforge.distribution;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import io.github.viniciusssantos.flagforge.distribution.internal.OutboxRecord;
import io.github.viniciusssantos.flagforge.distribution.internal.OutboxRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers recorded configuration events to in-process listeners.
 *
 * <p>Delivery is at least once. An event is marked delivered only after every listener accepted it,
 * so a crash between delivering and marking replays the event rather than losing it — which is why
 * listeners must be idempotent.
 *
 * <p>A pass runs in one transaction. Claimed rows stay locked until it commits, which is what keeps
 * a second relay from delivering the same event, and a failure anywhere returns the rows to
 * PENDING rather than leaving them stranded mid-flight.
 */
@Component
public class OutboxRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final List<ConfigurationChangeListener> listeners;
    private final OutboxRelayProperties properties;
    private final OutboxRelayMetrics metrics;
    private final Clock clock;

    OutboxRelay(
            OutboxRepository outboxRepository,
            List<ConfigurationChangeListener> listeners,
            OutboxRelayProperties properties,
            OutboxRelayMetrics metrics,
            Clock clock) {
        this.outboxRepository = outboxRepository;
        this.listeners = List.copyOf(listeners);
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Claims one batch and attempts to deliver each event.
     *
     * @return how many events were delivered in this pass
     */
    @Transactional
    public int deliverPending() {
        Instant now = Instant.now(clock);
        List<OutboxRecord> claimed = outboxRepository.claimDeliverable(now, properties.batchSize());
        int delivered = 0;
        for (OutboxRecord record : claimed) {
            if (deliver(record, now)) {
                delivered++;
            }
        }
        metrics.recordBacklog(outboxRepository.countPending());
        return delivered;
    }

    /**
     * Delivers one event, recording the outcome against that row.
     *
     * <p>A listener failure is caught per event so one bad event cannot stop the batch. The catch
     * is broad because a listener is arbitrary code and any failure means the same thing here:
     * this event was not delivered, so retry it.
     */
    private boolean deliver(OutboxRecord record, Instant now) {
        int attempts = record.deliveryAttempts() + 1;
        PublishedConfigurationEvent event = toEvent(record);
        try {
            for (ConfigurationChangeListener listener : listeners) {
                listener.onConfigurationPublished(event);
            }
            outboxRepository.markDelivered(record.eventId(), attempts);
            metrics.delivered();
            return true;
        } catch (RuntimeException exception) {
            recordFailure(record, attempts, now, exception);
            return false;
        }
    }

    private void recordFailure(
            OutboxRecord record,
            int attempts,
            Instant now,
            RuntimeException exception) {
        if (attempts >= properties.maxAttempts()) {
            outboxRepository.markFailed(record.eventId(), attempts);
            metrics.abandoned();
            LOGGER.error(
                    "Abandoning configuration event {} after {} attempts",
                    record.eventId(),
                    attempts,
                    exception);
            return;
        }
        Instant availableAt = now.plus(properties.backoffFor(attempts));
        outboxRepository.reschedule(record.eventId(), attempts, availableAt);
        metrics.retried();
        LOGGER.warn(
                "Retrying configuration event {} after attempt {}",
                record.eventId(),
                attempts,
                exception);
    }

    private static PublishedConfigurationEvent toEvent(OutboxRecord record) {
        return new PublishedConfigurationEvent(
                record.eventId(),
                record.organizationId(),
                record.projectId(),
                record.environmentId(),
                record.revisionId(),
                record.revisionNumber(),
                record.checksum(),
                record.occurredAt());
    }
}
