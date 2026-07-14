# FlagForge Agent Guide

This file contains durable repository instructions for coding agents. It applies to the entire repository. Add a nested `AGENTS.md` only when a subtree needs genuinely different commands or conventions.

## Start here

- Read `README.md` for the product scope and delivery status.
- Read `docs/ARCHITECTURE.md` before changing boundaries, dependencies, persistence, caching, or deployment topology.
- Read `docs/DOMAIN_MODEL.md` before changing flag, targeting, publication, evaluation, or tenant semantics.
- Read the relevant record in `docs/adr/` before revisiting an accepted architectural decision.
- Use `docs/ROADMAP.md` and the linked GitHub issue to keep the change inside the current milestone.
- Use `docs/TEST_STRATEGY.md` to select verification appropriate to the risk.

Do not duplicate these documents here. Update the source document when delivered behavior or an architectural decision changes.

## Product invariants

Preserve these properties in implementation, tests, and review:

- PostgreSQL is authoritative; caches are disposable accelerators.
- Published configuration revisions are complete and immutable.
- Rollback creates a new revision and never rewrites history.
- Evaluation is deterministic for the same configuration version and context.
- An evaluator never observes a partially published configuration.
- Increasing a percentage rollout preserves subjects already included in it.
- Tenant identity comes from trusted authentication context, not an untrusted request field alone.
- Cross-tenant reads, writes, evaluations, and information leakage are forbidden.
- Flag prerequisite cycles are rejected before publication.

If a requested change conflicts with an invariant, stop and surface the conflict instead of silently weakening the invariant.

## Architecture rules

- Keep the system a modular monolith until measured constraints justify extraction.
- Add modules through working vertical slices; do not create empty placeholder modules.
- Keep business rules inside the owning module and expose deliberate public contracts.
- Do not access another module's internal package, repository, or database table directly.
- Communicate across modules through public APIs or domain events as defined by the architecture.
- Keep domain behavior framework-light. Place transport, persistence, cache, and messaging details at the edges.
- Do not introduce Redis, a message broker, a second deployable service, or another production dependency without an issue-scoped need and an ADR when the decision is architectural.
- Avoid Java preview features unless the issue explicitly requires one and the trade-off is documented.

## Build and run

The supported toolchain is JDK 25 and the checked-in Maven Wrapper. Do not rely on a machine-wide Maven version.

Linux and macOS:

```bash
./mvnw --batch-mode --no-transfer-progress verify
./mvnw --projects apps/control-api spring-boot:run
```

Windows:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
.\mvnw.cmd --projects apps/control-api spring-boot:run
```

Operational smoke check:

```text
GET http://localhost:8080/actuator/health
GET http://localhost:8080/actuator/info
```

## Implementation workflow

1. Read the issue, relevant documentation, and nearby tests before editing.
2. State assumptions when the issue leaves domain behavior ambiguous.
3. Make the smallest cohesive change that satisfies the acceptance criteria.
4. Add or update tests with the behavior; do not defer essential coverage to a later issue.
5. Update OpenAPI, ADRs, or user-facing documentation when their contract changes.
6. Run the narrowest useful tests while iterating, then run the full Maven `verify` command before handoff.
7. Report what changed, what was verified, and any remaining limitation.

Do not suppress a failing test, weaken an assertion, disable a quality gate, or catch and ignore an error merely to make the build pass.

## Java conventions

- Use the `io.github.viniciusssantos.flagforge` package namespace.
- Prefer constructor injection and immutable state.
- Name types and methods with domain language from `docs/DOMAIN_MODEL.md`.
- Keep controllers thin; validation and orchestration must not replace domain behavior.
- Make time, identifiers, hashing, and randomness injectable when determinism matters.
- Return explicit errors at system boundaries; do not expose stack traces or internal exception details to clients.
- Keep formatting consistent with surrounding code until the repository adopts an automated formatter.

## Testing expectations

- Every behavior change needs an automated test at the lowest level that proves it.
- Domain invariants require focused unit or property-based tests.
- Module wiring, persistence, migrations, and external integrations require integration tests.
- Tenant-sensitive behavior requires positive and negative isolation tests.
- Percentage rollout logic requires deterministic boundary and monotonicity tests.
- Concurrency-sensitive publication requires a reproducible competing-writer test.
- A bug fix must include a regression test that fails without the fix.
- Prefer meaningful scenario coverage over arbitrary test counts or coverage percentages.

## Security and data handling

- Never commit credentials, tokens, private keys, real customer data, or populated local environment files.
- Never log authorization headers, API keys, full evaluation contexts, or stable high-cardinality subject identifiers.
- Treat tenant boundaries, management endpoints, API keys, webhooks, and audit history as security-sensitive.
- Use least privilege and deny by default at authentication and authorization boundaries.
- Keep example secrets obviously synthetic.

## Git and pull requests

- Never push directly to `main`; use an issue-linked branch and pull request.
- Preserve unrelated user changes and do not rewrite shared history.
- Use Conventional Commit-style messages such as `feat:`, `fix:`, `test:`, `docs:`, and `chore:`.
- Keep a pull request focused on one issue or one cohesive architectural slice.
- Include a summary, verification evidence, contract or migration impact, and any ADR added or superseded.
- Do not mark work complete while required CI checks are failing or pending.

## Review guidelines

Prioritize findings involving:

1. Tenant isolation, authorization bypass, secret exposure, or unsafe logging.
2. Broken determinism, immutable-revision guarantees, or rollout monotonicity.
3. Partial publication, transaction boundaries, concurrency races, or stale-cache correctness.
4. Module boundary violations and accidental infrastructure coupling.
5. API compatibility, migration safety, observability, and missing failure-path tests.
6. Documentation that no longer matches delivered behavior.

Do not request stylistic churn unless it improves correctness, maintainability, or consistency with an established repository convention.

## Definition of done

A change is complete when its acceptance criteria are met, relevant invariants are tested, the full build passes on JDK 25, public behavior and decisions are documented, tenant and failure paths are covered where applicable, and the pull request contains reproducible verification evidence.
