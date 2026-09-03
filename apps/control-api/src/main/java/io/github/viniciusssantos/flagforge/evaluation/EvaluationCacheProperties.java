package io.github.viniciusssantos.flagforge.evaluation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the in-process evaluation cache.
 *
 * @param enabled          whether evaluation reads through the cache at all. Off means every
 *                         evaluation goes to PostgreSQL, which is the behaviour that existed before
 *                         the cache and remains correct.
 * @param maximumSnapshots how many decoded documents to keep. Entries are per environment version,
 *                         not per flag, so this bounds distinct published versions held in memory.
 * @param pointerTtl       how long a version pointer is trusted without confirmation. Invalidation
 *                         normally refreshes it immediately; this bounds how long a missed
 *                         invalidation can go unnoticed on a single node.
 * @param stalenessBudget  how long a last-known-good document may still answer while PostgreSQL is
 *                         unavailable. Past it, evaluation fails explicitly rather than answering
 *                         from something too old to trust.
 */
@ConfigurationProperties(prefix = "flagforge.evaluation.cache")
public record EvaluationCacheProperties(
        boolean enabled,
        long maximumSnapshots,
        Duration pointerTtl,
        Duration stalenessBudget) {

    public EvaluationCacheProperties {
        maximumSnapshots = maximumSnapshots <= 0 ? 256 : maximumSnapshots;
        pointerTtl = pointerTtl == null ? Duration.ofSeconds(30) : pointerTtl;
        stalenessBudget = stalenessBudget == null ? Duration.ofMinutes(5) : stalenessBudget;
    }
}
