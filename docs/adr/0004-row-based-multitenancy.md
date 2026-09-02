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


---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# ADR 0004: Usar isolamento de tenant por linha inicialmente

- Status: Aceito
- Data: 2026-07-14

## Contexto

O isolamento por schema para cada tenant acrescenta complexidade de provisionamento, migração, pool de conexões e operação que não se justifica para o mercado-alvo inicial. Ainda assim, a plataforma exige proteção forte contra acesso entre tenants.

## Decisão

Armazenar tenants em tabelas compartilhadas, com um identificador explícito de organização nos registros pertencentes ao tenant. Derivar o contexto do tenant de credenciais autenticadas, incluir as fronteiras de tenant em consultas e constraints e adicionar testes adversariais de isolamento para todo caminho público de recurso.

O row-level security do PostgreSQL pode ser avaliado depois como defesa em profundidade, e não como substituto da autorização na aplicação.

## Consequências

### Positivas

- Migrações simples e desenvolvimento local facilitado.
- Conexões agrupadas eficientes.
- Métricas operacionais entre tenants mais fáceis, sem expor dados de tenant.

### Negativas

- A ausência de predicados de tenant pode ser perigosa.
- Recursos físicos compartilhados exigem cotas e controles de vizinho barulhento.
- O isolamento depende de várias salvaguardas testadas na aplicação.

</details>
