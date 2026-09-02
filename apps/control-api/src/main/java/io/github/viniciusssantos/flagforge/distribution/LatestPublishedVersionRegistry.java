package io.github.viniciusssantos.flagforge.distribution;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Tracks the newest configuration version each environment is known to have published.
 *
 * <p>This is the version hint that later cache work replaces with a shared one. It exists now
 * because the delivery guarantees are only meaningful against a consumer: duplicates must not
 * change it, and an event that arrives late must not move it backwards.
 *
 * <p>The registry is deliberately in-memory and lossy across restarts. It is a hint, never a source
 * of truth — PostgreSQL holds the authoritative pointer.
 */
@Component
public class LatestPublishedVersionRegistry implements ConfigurationChangeListener {

    private final Map<UUID, Long> latestByEnvironment = new ConcurrentHashMap<>();

    /**
     * Records a version, keeping the highest one seen.
     *
     * <p>{@code merge} with {@link Math#max} makes this both idempotent and safe against
     * out-of-order arrival without a lock: replaying an old event, or the same event twice, cannot
     * lower the recorded version.
     */
    @Override
    public void onConfigurationPublished(PublishedConfigurationEvent event) {
        latestByEnvironment.merge(
                event.environmentId(),
                event.revisionNumber(),
                Math::max);
    }

    public Optional<Long> latestRevisionNumber(UUID environmentId) {
        return Optional.ofNullable(latestByEnvironment.get(environmentId));
    }

    public void clear() {
        latestByEnvironment.clear();
    }
}
