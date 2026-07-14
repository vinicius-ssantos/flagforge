# ADR 0001: Start as a modular monolith

- Status: Accepted
- Date: 2026-07-14

## Context

FlagForge contains distinct domains and two workloads, but the initial team and traffic do not justify distributed operational complexity. Premature services would slow domain discovery, duplicate infrastructure, and make transactions and local development harder.

## Decision

Begin as a modular monolith using explicit Java package/module boundaries, Spring Modulith verification, and ArchUnit rules. Deliver vertical slices and keep module-owned data and APIs explicit.

The Control and Evaluation planes may have separate application entry points later while continuing to share repository and domain contracts.

## Consequences

### Positive

- Faster iteration and simpler local execution.
- Strong local transactions for publication.
- Architectural boundaries can be tested before network boundaries exist.
- Extraction remains possible from stable contracts.

### Negative

- Independent scaling is unavailable initially.
- Boundary enforcement depends on tests and conventions rather than separate deployments.
- A process-level failure can affect both planes in the first deployment stage.

## Revisit when

Evaluation load, availability objectives, security isolation, or release cadence demonstrably require independent deployment.

