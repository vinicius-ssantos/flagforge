# ADR 0003: Evaluate immutable versioned snapshots

- Status: Accepted
- Date: 2026-07-14

## Context

Evaluating flags directly from mutable normalized tables can expose partially updated rules, segments, or prerequisites. It also makes caching and rollback ambiguous.

## Decision

Each successful publication compiles a complete immutable snapshot for one project environment. The snapshot has a monotonic version, algorithm version, checksum, and all data required for evaluation.

Evaluators answer from exactly one snapshot version. Rollback creates a new publication based on a previous snapshot.

## Consequences

### Positive

- Atomic evaluation view.
- Straightforward cache keys and version comparison.
- Reproducible historical evaluation and rollback.
- SDK conformance can be tested against fixed snapshots.

### Negative

- Snapshot storage duplicates normalized data.
- Compilation and compatibility require versioning.
- Large environments may require compression or incremental transport later.

