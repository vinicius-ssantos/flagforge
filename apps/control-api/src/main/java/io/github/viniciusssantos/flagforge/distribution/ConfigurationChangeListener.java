package io.github.viniciusssantos.flagforge.distribution;

/**
 * Receives configuration versions as the relay delivers them.
 *
 * <p>Delivery is at least once: a listener will occasionally see the same event twice, and may see
 * a newer version before an older one. Implementations must therefore be idempotent and must
 * compare versions rather than trusting arrival order — {@link LatestPublishedVersionRegistry}
 * shows the shape.
 *
 * <p>A listener that throws signals a transient failure. The relay reschedules that event with
 * backoff, so a listener must not throw for an event it has simply decided to ignore.
 */
public interface ConfigurationChangeListener {

    void onConfigurationPublished(PublishedConfigurationEvent event);
}
