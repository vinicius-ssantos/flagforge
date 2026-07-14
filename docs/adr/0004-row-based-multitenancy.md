# ADR 0004: Use row-based tenant isolation initially

- Status: Accepted
- Date: 2026-07-14

## Context

Schema-per-tenant isolation adds provisioning, migration, connection-pool, and operational complexity that is not justified for the initial target market. The platform still requires strong protection against cross-tenant access.

## Decision

Store tenants in shared tables with an explicit organization identifier on tenant-owned records. Derive tenant context from authenticated credentials, include tenant boundaries in queries and constraints, and add adversarial isolation tests for every public resource path.

PostgreSQL row-level security may be evaluated later as defense in depth, not as a substitute for application authorization.

## Consequences

### Positive

- Simple migrations and local development.
- Efficient pooled connections.
- Easier cross-tenant operational metrics without exposing tenant data.

### Negative

- Missing tenant predicates can be dangerous.
- Shared physical resources require quotas and noisy-neighbor controls.
- Isolation depends on multiple tested application safeguards.

