# ADR 0005: Keep PostgreSQL authoritative and layer caches

- Status: Accepted
- Date: 2026-07-14

## Context

Flag evaluation benefits from memory-speed reads, but treating Redis as authoritative would weaken durability, revision history, and recovery guarantees. Local caches alone create stale replicas after publication.

## Decision

Use PostgreSQL as the only source of truth. Introduce caches after evaluator correctness is proven:

1. Caffeine L1 for immutable snapshots in each evaluator.
2. Redis L2 for shared snapshot reuse and current-version hints.
3. Transactional outbox and best-effort version notifications.
4. Periodic reconciliation to repair missed notifications.

Cache entries are keyed by full tenant/environment identity and immutable version. Last-known-good behavior has a configured staleness budget and is visible in the evaluation reason.

## Consequences

### Positive

- Durable recovery from PostgreSQL.
- Fast warm evaluation paths.
- Missed invalidations converge automatically.
- Immutable entries reduce cache coherence complexity.

### Negative

- Evaluation replicas are eventually consistent.
- Two cache layers require telemetry and failure tests.
- Redis adds operational cost only when the distributed phase begins.

