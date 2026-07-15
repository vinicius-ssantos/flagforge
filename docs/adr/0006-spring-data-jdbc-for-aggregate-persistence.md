# ADR 0006: Use Spring Data JDBC for aggregate persistence

- Status: Accepted
- Date: 2026-07-15

## Context

FlagForge persists tenant-scoped aggregates, immutable published revisions, compiled evaluation snapshots, audit records, rollout state, and transactional outbox entries in PostgreSQL.

The persistence approach must preserve explicit aggregate boundaries and transaction behavior. The project also needs direct control over concurrency-sensitive SQL, append-only records, bulk snapshot reads, and operational queries. The initial choice is between Spring Data JDBC and Spring Data JPA with Hibernate.

## Decision

Use Spring Data JDBC as the default repository technology for aggregate persistence.

- Define one repository per aggregate root rather than repositories for every table.
- Keep cross-aggregate references as identifiers or deliberate application-level contracts.
- Use Flyway as the only schema creation and migration mechanism.
- Use `JdbcClient`, `JdbcTemplate`, or custom repository implementations when a query, lock, bulk operation, snapshot load, outbox claim, or projection is clearer as explicit SQL.
- Do not introduce JPA entities, Hibernate schema generation, lazy-loading proxies, or Open Session in View.
- Keep transaction boundaries in application services and make optimistic concurrency explicit in the database model.

## Rationale

Spring Data JDBC aligns with the project's aggregate-first domain model and keeps persistence behavior visible. It avoids implicit lazy loading, persistence-context side effects, and accidental graph traversal across module or tenant boundaries.

The choice also fits FlagForge's most important database paths:

- immutable revision and snapshot insertion;
- explicit compare-and-set publication updates;
- append-only audit and outbox writes;
- deterministic loading of one complete snapshot version;
- PostgreSQL-specific constraints and concurrency tests.

## Consequences

### Positive

- Aggregate ownership and repository boundaries remain explicit.
- SQL and transaction behavior are easier to inspect during concurrency and failure analysis.
- Domain objects do not require JPA proxies or bidirectional relationships.
- PostgreSQL-specific features can be introduced deliberately behind repository contracts.
- Persistence remains compatible with the modular-monolith boundaries.

### Negative

- Complex read models may require explicit SQL and mapping code.
- Spring Data JDBC treats entities reachable from an aggregate root as part of that aggregate and may delete and recreate child rows during aggregate updates.
- Large mutable collections must not be persisted through naive aggregate replacement.
- There is no automatic dirty checking or lazy relationship loading.

## Guardrails

- Keep aggregates small enough to update atomically.
- Persist large immutable snapshots through dedicated insert/load components rather than a large mutable aggregate save.
- Use projections for read-heavy console and operational queries.
- Add database constraints for every invariant that can be enforced relationally.
- Test migrations, tenant scoping, optimistic concurrency, and transaction rollback against PostgreSQL with Testcontainers.

## Alternatives considered

### Spring Data JPA and Hibernate

Rejected as the default because its persistence context, lazy relationships, and graph-oriented mapping can obscure database access and aggregate boundaries. JPA remains viable for applications dominated by navigable relational graphs, but that is not the primary FlagForge workload.

### Plain Spring JDBC only

Rejected as the sole approach because Spring Data JDBC provides useful repository and aggregate conventions while still allowing explicit SQL where needed.

### jOOQ

Deferred. It may be reconsidered if the project develops a large set of complex type-safe SQL projections that materially exceeds the value of Spring Data JDBC plus `JdbcClient`.

## Revisit when

Reconsider this decision if executable evidence shows that:

- most persistence code becomes custom mapping boilerplate;
- aggregate updates cannot be modeled safely without excessive manual SQL;
- complex read queries dominate development and would materially benefit from generated type-safe SQL;
- a measured use case demonstrates that JPA's unit-of-work model is a better fit without weakening module or tenant boundaries.
