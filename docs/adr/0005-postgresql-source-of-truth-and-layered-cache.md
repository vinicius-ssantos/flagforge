# ADR 0005: Keep PostgreSQL authoritative and layer caches

- Status: Accepted
- Date: 2026-07-14

## Context

Flag evaluation benefits from memory-speed reads, but treating Redis as authoritative would weaken durability, revision history, and recovery guarantees. Local caches alone create stale replicas after publication.

## Decision

Use PostgreSQL as the only source of truth. Introduce caches after evaluator correctness is proven:

1. Caffeine L1 for immutable snapshots in each evaluator.
2. Redis L2 for shared snapshot reuse and current-version hints.
3. Transactional outbox and best-effort version notifications.
4. Periodic reconciliation to repair missed notifications.

Cache entries are keyed by full tenant/environment identity and immutable version. Last-known-good behavior has a configured staleness budget and is visible in the evaluation reason.

## Consequences

### Positive

- Durable recovery from PostgreSQL.
- Fast warm evaluation paths.
- Missed invalidations converge automatically.
- Immutable entries reduce cache coherence complexity.

### Negative

- Evaluation replicas are eventually consistent.
- Two cache layers require telemetry and failure tests.
- Redis adds operational cost only when the distributed phase begins.


---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# ADR 0005: Manter o PostgreSQL autoritativo e usar caches em camadas

- Status: Aceito
- Data: 2026-07-14

## Contexto

A avaliação de flags se beneficia de leituras em velocidade de memória, mas tratar o Redis como autoritativo enfraqueceria as garantias de durabilidade, histórico de revisões e recuperação. Caches locais isolados criam réplicas defasadas após a publicação.

## Decisão

Usar o PostgreSQL como única fonte da verdade. Introduzir caches depois que a correção do avaliador estiver comprovada:

1. Caffeine L1 para snapshots imutáveis em cada avaliador.
2. Redis L2 para reuso compartilhado de snapshots e dicas de versão atual.
3. Outbox transacional e notificações de versão em regime de melhor esforço.
4. Reconciliação periódica para reparar notificações perdidas.

As entradas de cache são chaveadas pela identidade completa de tenant/ambiente e pela versão imutável. O comportamento de último estado bom conhecido tem um orçamento configurado de defasagem e é visível na razão da avaliação.

## Consequências

### Positivas

- Recuperação durável a partir do PostgreSQL.
- Caminhos rápidos de avaliação em cache quente.
- Invalidações perdidas convergem automaticamente.
- Entradas imutáveis reduzem a complexidade de coerência de cache.

### Negativas

- As réplicas de avaliação são eventualmente consistentes.
- Duas camadas de cache exigem telemetria e testes de falha.
- O Redis acrescenta custo operacional apenas quando a fase distribuída começa.

</details>
