# Delivery Roadmap

The roadmap is organized around demonstrable outcomes rather than infrastructure layers. Each milestone ends with a vertical scenario that can be exercised from the UI or API.

## M0 — Foundation

### Outcome

A reproducible Java project with enforced modular boundaries and a production-minded development baseline.

### Scope

- Java 25 and Maven multi-module foundation.
- Spring Boot and Spring Modulith baseline.
- Architecture verification with Modulith and ArchUnit.
- PostgreSQL and Flyway development environment.
- Docker Compose local dependencies.
- CI for compilation, tests, architecture checks, and formatting.
- Structured logging, health endpoints, and tracing baseline.
- Secret-safe configuration and documented local setup.

### Exit criteria

- A new contributor can run the service and tests from documented commands.
- CI passes from a clean checkout.
- An intentional cross-module dependency violation fails a test.
- No production credential is required for local development.

## M1 — Deterministic Evaluator

### Outcome

An authenticated tenant can define a boolean feature flag and evaluate it using ordered rules and stable percentage rollout.

### Scope

- Organizations, memberships, projects, and environments.
- Initial RBAC and environment-scoped SDK key.
- Boolean flags and named variants.
- Typed evaluation context.
- Ordered rules and a minimal operator set.
- Stable hashing specification and test vectors.
- Evaluation API with reason metadata.
- Minimal web console and Evaluation Playground.

### Exit criteria

- Tenant isolation tests cover every public resource path.
- Fixed evaluation vectors produce stable results.
- Percentage rollout demonstrates approximate distribution over a documented sample.
- The playground explains the winning rule and bucket.

## M2 — Safe Publishing

### Outcome

Teams edit drafts and publish complete immutable revisions without silent concurrent overwrites.

### Scope

- Draft and published representations.
- Complete snapshot compilation.
- Optimistic concurrency on publication.
- Validation of rules and prerequisite cycles.
- Append-only audit trail.
- Diff, history, and rollback.
- Protected environment policy and optional approval.

### Exit criteria

- Concurrent publication attempts cannot silently overwrite each other.
- An evaluator never observes a partial revision.
- Rollback produces a new auditable revision.
- Production policy failures return actionable errors.

## M3 — Distributed Evaluation

### Outcome

Replicated evaluators serve versioned snapshots efficiently and recover from missed invalidations or temporary dependency failure.

### Scope

- Caffeine L1 cache.
- Redis L2 cache and version pointer.
- Transactional outbox for published version events.
- Best-effort invalidation and periodic reconciliation.
- Request coalescing and stampede protection.
- Last-known-good snapshot with bounded staleness.
- Java SDK and OpenFeature provider.
- Evaluation and propagation telemetry.

### Exit criteria

- Redis loss has a documented, tested degradation mode.
- A deliberately dropped invalidation is repaired by reconciliation.
- Duplicate events are harmless.
- Benchmarks publish environment, dataset, commands, and percentile results.
- The Java SDK passes shared evaluation conformance vectors.

## M4 — Progressive Delivery

### Outcome

An operator can schedule, observe, pause, resume, and roll back a staged rollout.

### Scope

- Versioned rollout plans and steps.
- Manual and scheduled progression.
- Pause, resume, cancel, and rollback.
- Pluggable health-gate contract.
- Live rollout dashboard.
- Exposure events with privacy controls.
- Flag ownership, expiration, and cleanup reminders.
- End-to-end portfolio demo and architecture review.

### Exit criteria

- A checkout demo progresses through at least three percentage steps.
- A failed health gate pauses progression and preserves an audit trail.
- An emergency rollback changes effective behavior within a measured interval.
- The demo can be reproduced locally from documented commands.

## Deferred ideas

- TypeScript SDK.
- Configuration as code and pull-request validation.
- Multi-region evaluation.
- Full experimentation statistics.
- External observability provider integrations.
- Enterprise identity federation and SCIM.

Deferred work is not part of the initial portfolio completion criteria.

