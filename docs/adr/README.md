# Architecture Decision Records

ADRs capture decisions that materially affect architecture, security, data ownership, or operational behavior.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-modular-monolith-first.md) | Start as a modular monolith | Accepted |
| [0002](0002-logical-control-and-evaluation-planes.md) | Separate Control and Evaluation planes logically | Accepted |
| [0003](0003-immutable-versioned-snapshots.md) | Evaluate immutable versioned snapshots | Accepted |
| [0004](0004-row-based-multitenancy.md) | Use row-based tenant isolation initially | Accepted |
| [0005](0005-postgresql-source-of-truth-and-layered-cache.md) | Keep PostgreSQL authoritative and layer caches | Accepted |

## ADR template

New records should include:

- Status and date.
- Context and forces.
- Decision.
- Consequences, including negative consequences.
- Alternatives considered.
- Conditions that would justify revisiting the decision.

