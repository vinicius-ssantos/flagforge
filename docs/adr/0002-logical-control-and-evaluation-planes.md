# ADR 0002: Separate Control and Evaluation planes logically

- Status: Accepted
- Date: 2026-07-14

## Context

Administrative changes prioritize correctness, authorization, validation, and audit. Evaluation prioritizes low latency, high availability, predictable reads, and graceful degradation.

## Decision

Model Control Plane and Evaluation Plane as separate logical applications and contracts from the start. They may run in the same process initially, but evaluation cannot depend on draft tables or partially assembled configuration.

The handoff between planes is an immutable published snapshot and version notification.

## Consequences

### Positive

- Read and write concerns remain clear.
- The evaluator can degrade independently using last-known-good data.
- Future independent deployment does not require redesigning the domain boundary.

### Negative

- Snapshot compilation and distribution become explicit responsibilities.
- Some data is duplicated in read-optimized form.
- Eventual consistency must be documented and observable.

