package io.github.viniciusssantos.flagforge.distribution;

import java.sql.Timestamp;

import io.github.viniciusssantos.flagforge.distribution.internal.OutboxRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records configuration events for later delivery.
 *
 * <p>This module owns the outbox table. Publication reaches it through this contract rather than
 * writing the table itself, so delivery state stays behind one boundary.
 *
 * <p>The propagation is {@link Propagation#MANDATORY} on purpose: an event may only be recorded
 * inside the transaction that produced the revision it describes. A caller without a transaction
 * fails loudly instead of silently creating an event for a publication that might roll back.
 */
@Service
public class ConfigurationEventOutbox {

    private final OutboxRepository outboxRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    ConfigurationEventOutbox(
            OutboxRepository outboxRepository,
            ApplicationEventPublisher applicationEventPublisher) {
        this.outboxRepository = outboxRepository;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * Writes the event and asks the relay to run once the caller's transaction commits.
     *
     * <p>The signal is published inside the transaction but only acted on after commit, so an event
     * from a publication that rolls back is never delivered. Losing the signal costs latency, not
     * correctness: the recovery poll still finds the row.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(PublishedConfigurationEvent event) {
        outboxRepository.insertPending(event, Timestamp.from(event.occurredAt()));
        applicationEventPublisher.publishEvent(new ConfigurationEventRecorded(event));
    }
}
