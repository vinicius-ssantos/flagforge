# Contributing to FlagForge

FlagForge is currently an architecture-led portfolio project. Contributions should preserve its emphasis on explicit domain invariants, tenant isolation, reproducible evidence, and earned complexity.

## Before opening a change

- Search existing issues and ADRs.
- Use an issue for behavior changes or architectural work.
- Propose an ADR when a change affects module boundaries, consistency, security, persistence ownership, public contracts, or failure semantics.
- Keep each pull request focused on one coherent outcome.

## Development workflow

The executable project commands will be documented with the M0 foundation implementation. Until then, do not add speculative setup instructions.

Once the build exists, every pull request is expected to run:

- Formatting and static analysis.
- Unit and module tests.
- Architecture verification.
- Integration tests relevant to the change.

## Pull request expectations

Describe:

- The problem being solved.
- User-visible and operational behavior.
- Invariants added or affected.
- Failure and fallback behavior.
- Tests and evidence.
- Security, tenant isolation, and observability impact.
- Documentation or ADR changes.

## Commit style

Use clear, imperative commits. Conventional prefixes are encouraged:

```text
feat: add deterministic rollout allocation
fix: prevent stale version pointer regression
test: cover cross-tenant segment lookup
docs: record snapshot versioning decision
chore: configure Java toolchain
```

## Design rules

- PostgreSQL remains authoritative.
- No direct access to another module's internal package or owned tables.
- Do not add distributed infrastructure without a use case and failure test.
- Do not use feature flags as authorization.
- Do not log credentials or complete targeting contexts.
- Every tenant-owned lookup must prove its tenant boundary.

## Reporting security issues

Do not open public issues for vulnerabilities. Follow [SECURITY.md](SECURITY.md).

