# Test Strategy

FlagForge prioritizes evidence for domain invariants, tenant isolation, concurrency, and failure recovery over an arbitrary test count or coverage percentage.

## Test layers

| Layer | Purpose |
|---|---|
| Unit | Rule operators, normalization, reason selection, hash/bucket mapping |
| Property-based | Determinism, rollout monotonicity, allocation bounds, parser robustness |
| Module | Application use cases and module contracts with controlled dependencies |
| Architecture | Module cycles, internal package access, dependency policy |
| Integration | PostgreSQL constraints, Flyway migrations, Redis behavior, outbox |
| Concurrency | Competing publications, cache fill coalescing, idempotent delivery |
| Security | Cross-tenant access, role boundaries, key rotation and revocation |
| Contract | OpenAPI and shared evaluator/SDK conformance vectors |
| End-to-end | Console-to-publication-to-SDK user journeys |
| Performance | Reproducible evaluation latency, throughput, cache and propagation tests |

## Mandatory scenarios

### Determinism

- Same snapshot and normalized context return the same result across repeated runs.
- Java SDK and server evaluator pass the same fixed vectors.
- Rollout increases retain subjects that were previously included.
- Unknown or missing attributes follow declared operator semantics.

### Tenant isolation

- Organization A cannot fetch Organization B resources by guessed identifier.
- Organization A cannot reference Organization B segments or prerequisites.
- An SDK key is valid only for its environment and permitted operation.
- Cache keys and lookups include the full tenant boundary.

### Publication and concurrency

- Two writers publishing the same expected version produce one success and one conflict.
- Publication persists revision, pointer, audit, and outbox atomically.
- Invalid prerequisite cycles are rejected before state changes.
- Rollback creates a new version without mutating old content.

### Cache and delivery

- Duplicate version events are idempotent.
- Out-of-order events never replace a newer cached version with an older one.
- A missed invalidation converges through reconciliation.
- Concurrent misses for the same snapshot are coalesced.
- Redis unavailability follows documented fallback behavior.
- Stale snapshots are never served beyond the configured budget.

### Security and privacy

- Stored credentials are hashed and plaintext is shown only at creation.
- Logs and traces do not contain credentials or complete sensitive contexts.
- Metric labels do not contain targeting keys.
- Revoked keys fail subsequent authorization.

## Performance methodology

Every published performance claim includes:

- Commit SHA and build profile.
- Hardware and container limits.
- Java runtime and garbage collector.
- Dataset size and rule complexity.
- Cold and warm cache distinction.
- Request concurrency and duration.
- p50, p95, p99, throughput, and error rate.
- Commands required to reproduce the run.

Targets are set after a baseline exists. Marketing-style latency claims without methodology are not accepted.

## Definition of done

A change is done when relevant invariants and negative cases are tested, public behavior is documented, telemetry exists for operationally significant paths, and tests remain deterministic from a clean environment.

