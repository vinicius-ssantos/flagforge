package io.github.viniciusssantos.flagforge.distribution;

import java.util.Objects;

/**
 * Signals that a configuration event was written to the outbox in the current transaction.
 *
 * <p>Published inside the transaction but only acted on after it commits, so nothing reacts to a
 * publication that rolls back.
 *
 * <p>Two very different consumers use this. The relay only needs to know that work exists. A local
 * cache needs to know what changed, so that the node which just published does not wait for its own
 * asynchronous delivery to notice — the relay's job is telling the <em>other</em> nodes.
 */
public record ConfigurationEventRecorded(PublishedConfigurationEvent event) {

    public ConfigurationEventRecorded {
        Objects.requireNonNull(event, "event is required");
    }
}
