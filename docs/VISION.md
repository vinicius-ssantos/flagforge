# Product Vision

## Vision

FlagForge gives software teams runtime control over how changes reach customers. It turns a risky all-at-once release into a controlled, observable, and reversible process.

## Problem statement

Teams that deploy frequently need to answer questions that deployment tooling alone does not solve:

- Can this code be deployed without immediately exposing it?
- Can the feature be enabled for internal users or one customer first?
- Can exposure increase gradually while behavior is monitored?
- Can an operator stop the change without waiting for a rebuild and redeploy?
- Can the team prove who changed production behavior and why?
- Can support explain why one customer received a different result?

Home-grown flags often begin as environment variables or database booleans. They become risky when configuration has no ownership, history, deterministic targeting, tenant isolation, or reliable propagation.

## Ideal customer profile

The initial ideal user is a small or medium-sized SaaS engineering organization that:

- Operates multiple environments.
- Deploys at least several times per week.
- Serves multiple customer organizations or user segments.
- Already uses ad-hoc flags or remote configuration.
- Needs better auditability but finds enterprise platforms too expensive or complex.
- Builds primarily on Java/Spring or wants vendor-neutral OpenFeature integration.

## Personas and jobs to be done

| Persona | Job to be done |
|---|---|
| Application developer | Merge and deploy code safely before exposing it |
| Platform engineer | Provide a reliable, standardized flag service to many teams |
| SRE/operator | Limit blast radius and disable dangerous behavior quickly |
| QA engineer | Activate hidden behavior for controlled validation |
| Product manager | Coordinate staged availability without scheduling deployments |
| Support engineer | Explain the effective configuration for a specific customer |
| Security/auditor | Verify who changed production behavior and whether it was approved |

## Value proposition

For software teams that release frequently, FlagForge is a progressive delivery platform that makes runtime changes gradual, explainable, and reversible. Unlike ad-hoc flags, it provides deterministic evaluation, immutable versions, tenant-aware governance, and a vendor-neutral integration path.

## Differentiation hypothesis

FlagForge will not compete by claiming the largest feature list. Its portfolio and product differentiation are:

- OpenFeature-native integration rather than a proprietary-only SDK.
- Java/Spring-first developer experience.
- An Evaluation Playground that exposes the complete decision trace.
- SaaS and self-hosted-friendly architecture.
- Governance features that remain understandable for smaller teams.
- Explicit consistency, staleness, and failure semantics.

## Success measures

Measurements are defined with a reproducible environment before numeric targets are committed. The platform should track:

- Evaluation latency percentiles.
- Cache hit ratios per layer.
- Configuration propagation delay.
- Stale or fallback evaluations.
- Rollout pause and rollback actions.
- Flag age and overdue cleanup.
- Publication failures and optimistic concurrency conflicts.

## Non-goals

The initial product will not:

- Replace authentication, authorization, entitlements, or permanent business rules.
- Provide a complete statistical experimentation platform.
- Support every SDK ecosystem at launch.
- Guarantee globally strong consistency at arbitrary scale.
- Store authoritative configuration only in Redis.
- Begin as a fleet of microservices.
- Use machine learning to decide rollout allocation.

## Product risks

| Risk | Mitigation |
|---|---|
| Becoming a CRUD clone | Prioritize evaluation semantics, publishing safety, and failure behavior |
| Flag debt | Ownership, expiration, archive workflows, and age metrics |
| Tenant data exposure | Auth-derived tenant context, constraints, authorization, and adversarial tests |
| Stale configuration | Versioned snapshots, bounded staleness, invalidation plus reconciliation |
| Overengineering | Deliver vertical slices and require evidence before extracting services |
| Vendor lock-in | OpenFeature provider and exportable configuration model |

