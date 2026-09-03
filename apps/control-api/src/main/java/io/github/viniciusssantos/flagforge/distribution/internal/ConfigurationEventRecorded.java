package io.github.viniciusssantos.flagforge.distribution.internal;

/**
 * Signals that an event was written to the outbox in the current transaction.
 *
 * <p>Carries no data on purpose. It only wakes the relay, which then reads committed rows — passing
 * the event itself would invite a listener to act on something the transaction may still roll back.
 */
public record ConfigurationEventRecorded() {
}
