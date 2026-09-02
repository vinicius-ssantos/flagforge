# Architecture Decision Records

ADRs capture decisions that materially affect architecture, security, data ownership, or operational behavior.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-modular-monolith-first.md) | Start as a modular monolith | Accepted |
| [0002](0002-logical-control-and-evaluation-planes.md) | Separate Control and Evaluation planes logically | Accepted |
| [0003](0003-immutable-versioned-snapshots.md) | Evaluate immutable versioned snapshots | Accepted |
| [0004](0004-row-based-multitenancy.md) | Use row-based tenant isolation initially | Accepted |
| [0005](0005-postgresql-source-of-truth-and-layered-cache.md) | Keep PostgreSQL authoritative and layer caches | Accepted |
| [0006](0006-spring-data-jdbc-for-aggregate-persistence.md) | Use Spring Data JDBC for aggregate persistence | Accepted |
| [0007](0007-external-oidc-identity-with-per-request-organization.md) | Authenticate operators with external OIDC and select the organization per request | Accepted |
| [0008](0008-outbox-ownership-and-at-least-once-delivery.md) | Move outbox ownership to distribution and deliver at least once | Accepted |

## ADR template

New records should include:

- Status and date.
- Context and forces.
- Decision.
- Consequences, including negative consequences.
- Alternatives considered.
- Conditions that would justify revisiting the decision.


---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# Architecture Decision Records

Os ADRs registram decisões que afetam materialmente arquitetura, segurança, propriedade dos dados ou comportamento operacional.

| ADR | Decisão | Status |
|---|---|---|
| [0001](0001-modular-monolith-first.md) | Começar como monólito modular | Aceito |
| [0002](0002-logical-control-and-evaluation-planes.md) | Separar logicamente os planos de Controle e de Avaliação | Aceito |
| [0003](0003-immutable-versioned-snapshots.md) | Avaliar snapshots versionados imutáveis | Aceito |
| [0004](0004-row-based-multitenancy.md) | Usar isolamento de tenant por linha inicialmente | Aceito |
| [0005](0005-postgresql-source-of-truth-and-layered-cache.md) | Manter o PostgreSQL autoritativo e usar caches em camadas | Aceito |
| [0006](0006-spring-data-jdbc-for-aggregate-persistence.md) | Usar Spring Data JDBC para persistência de agregados | Aceito |
| [0007](0007-external-oidc-identity-with-per-request-organization.md) | Autenticar operadores com OIDC externo e selecionar a organização por requisição | Aceito |
| [0008](0008-outbox-ownership-and-at-least-once-delivery.md) | Mover a propriedade do outbox para distribution e entregar ao menos uma vez | Aceito |

## Modelo de ADR

Novos registros devem incluir:

- Status e data.
- Contexto e forças em jogo.
- Decisão.
- Consequências, incluindo as negativas.
- Alternativas consideradas.
- Condições que justificariam revisitar a decisão.

</details>
