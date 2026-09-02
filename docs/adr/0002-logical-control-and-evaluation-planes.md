# ADR 0002: Separate Control and Evaluation planes logically

- Status: Accepted
- Date: 2026-07-14

## Context

Administrative changes prioritize correctness, authorization, validation, and audit. Evaluation prioritizes low latency, high availability, predictable reads, and graceful degradation.

## Decision

Model Control Plane and Evaluation Plane as separate logical applications and contracts from the start. They may run in the same process initially, but evaluation cannot depend on draft tables or partially assembled configuration.

The handoff between planes is an immutable published snapshot and version notification.

## Consequences

### Positive

- Read and write concerns remain clear.
- The evaluator can degrade independently using last-known-good data.
- Future independent deployment does not require redesigning the domain boundary.

### Negative

- Snapshot compilation and distribution become explicit responsibilities.
- Some data is duplicated in read-optimized form.
- Eventual consistency must be documented and observable.


---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# ADR 0002: Separar logicamente os planos de Controle e de Avaliação

- Status: Aceito
- Data: 2026-07-14

## Contexto

Mudanças administrativas priorizam correção, autorização, validação e auditoria. A avaliação prioriza baixa latência, alta disponibilidade, leituras previsíveis e degradação graciosa.

## Decisão

Modelar o Plano de Controle e o Plano de Avaliação como aplicações e contratos lógicos separados desde o início. Eles podem rodar no mesmo processo inicialmente, mas a avaliação não pode depender de tabelas de rascunho nem de configuração parcialmente montada.

A transferência entre os planos é um snapshot publicado imutável e uma notificação de versão.

## Consequências

### Positivas

- As preocupações de leitura e de escrita permanecem claras.
- O avaliador pode degradar de forma independente usando dados de último estado bom conhecido.
- Um deploy independente no futuro não exige reprojetar a fronteira de domínio.

### Negativas

- A compilação e a distribuição de snapshots se tornam responsabilidades explícitas.
- Alguns dados são duplicados em forma otimizada para leitura.
- A consistência eventual precisa ser documentada e observável.

</details>
