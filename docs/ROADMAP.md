# Delivery Roadmap

The roadmap is organized around demonstrable outcomes rather than infrastructure layers. Each milestone ends with a vertical scenario that can be exercised from the UI or API.

## M0 — Foundation

### Outcome

A reproducible Java project with enforced modular boundaries and a production-minded development baseline.

### Scope

- Java 25 and Maven multi-module foundation.
- Spring Boot and Spring Modulith baseline.
- Architecture verification with Modulith and ArchUnit.
- PostgreSQL and Flyway development environment.
- Docker Compose local dependencies.
- CI for compilation, tests, architecture checks, and formatting.
- Structured logging, health endpoints, and tracing baseline.
- Secret-safe configuration and documented local setup.

### Exit criteria

- A new contributor can run the service and tests from documented commands.
- CI passes from a clean checkout.
- An intentional cross-module dependency violation fails a test.
- No production credential is required for local development.

## M1 — Deterministic Evaluator

### Outcome

An authenticated tenant can define a boolean feature flag and evaluate it using ordered rules and stable percentage rollout.

### Scope

- Organizations, memberships, projects, and environments.
- Initial RBAC and environment-scoped SDK key.
- Boolean flags and named variants.
- Typed evaluation context.
- Ordered rules and a minimal operator set.
- Stable hashing specification and test vectors.
- Evaluation API with reason metadata.
- Minimal web console and Evaluation Playground.

### Exit criteria

- Tenant isolation tests cover every public resource path.
- Fixed evaluation vectors produce stable results.
- Percentage rollout demonstrates approximate distribution over a documented sample.
- The playground explains the winning rule and bucket.

## M2 — Safe Publishing

### Outcome

Teams edit drafts and publish complete immutable revisions without silent concurrent overwrites.

### Scope

- Draft and published representations.
- Complete snapshot compilation.
- Optimistic concurrency on publication.
- Validation of rules and prerequisite cycles.
- Append-only audit trail.
- Diff, history, and rollback.
- Protected environment policy and optional approval.

### Exit criteria

- Concurrent publication attempts cannot silently overwrite each other.
- An evaluator never observes a partial revision.
- Rollback produces a new auditable revision.
- Production policy failures return actionable errors.

## M3 — Distributed Evaluation

### Outcome

Replicated evaluators serve versioned snapshots efficiently and recover from missed invalidations or temporary dependency failure.

### Scope

- Caffeine L1 cache.
- Redis L2 cache and version pointer.
- Transactional outbox for published version events.
- Best-effort invalidation and periodic reconciliation.
- Request coalescing and stampede protection.
- Last-known-good snapshot with bounded staleness.
- Java SDK and OpenFeature provider.
- Evaluation and propagation telemetry.

### Exit criteria

- Redis loss has a documented, tested degradation mode.
- A deliberately dropped invalidation is repaired by reconciliation.
- Duplicate events are harmless.
- Benchmarks publish environment, dataset, commands, and percentile results.
- The Java SDK passes shared evaluation conformance vectors.

## M4 — Progressive Delivery

### Outcome

An operator can schedule, observe, pause, resume, and roll back a staged rollout.

### Scope

- Versioned rollout plans and steps.
- Manual and scheduled progression.
- Pause, resume, cancel, and rollback.
- Pluggable health-gate contract.
- Live rollout dashboard.
- Exposure events with privacy controls.
- Flag ownership, expiration, and cleanup reminders.
- End-to-end portfolio demo and architecture review.

### Exit criteria

- A checkout demo progresses through at least three percentage steps.
- A failed health gate pauses progression and preserves an audit trail.
- An emergency rollback changes effective behavior within a measured interval.
- The demo can be reproduced locally from documented commands.

## Deferred ideas

- TypeScript SDK.
- Configuration as code and pull-request validation.
- Multi-region evaluation.
- Full experimentation statistics.
- External observability provider integrations.
- Enterprise identity federation and SCIM.

Deferred work is not part of the initial portfolio completion criteria.


---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# Roadmap de Entrega

O roadmap é organizado em torno de resultados demonstráveis, e não de camadas de infraestrutura. Cada marco termina com um cenário vertical que pode ser exercitado pela UI ou pela API.

## M0 — Fundação

### Resultado

Um projeto Java reprodutível, com fronteiras modulares impostas por verificação e um baseline de desenvolvimento com mentalidade de produção.

### Escopo

- Fundação Java 25 e Maven multimódulo.
- Baseline de Spring Boot e Spring Modulith.
- Verificação de arquitetura com Modulith e ArchUnit.
- Ambiente de desenvolvimento com PostgreSQL e Flyway.
- Dependências locais via Docker Compose.
- CI para compilação, testes, checagens de arquitetura e formatação.
- Logging estruturado, endpoints de health e baseline de tracing.
- Configuração segura quanto a segredos e setup local documentado.

### Critérios de saída

- Uma pessoa contribuidora nova consegue executar o serviço e os testes a partir de comandos documentados.
- A CI passa a partir de um checkout limpo.
- Uma violação intencional de dependência entre módulos faz um teste falhar.
- Nenhuma credencial de produção é necessária para o desenvolvimento local.

## M1 — Avaliador Determinístico

### Resultado

Um tenant autenticado consegue definir uma feature flag booleana e avaliá-la usando regras ordenadas e rollout percentual estável.

### Escopo

- Organizações, associações, projetos e ambientes.
- RBAC inicial e chave de SDK com escopo de ambiente.
- Flags booleanas e variantes nomeadas.
- Contexto de avaliação tipado.
- Regras ordenadas e um conjunto mínimo de operadores.
- Especificação de hashing estável e vetores de teste.
- API de avaliação com metadados de razão.
- Console web mínimo e Evaluation Playground.

### Critérios de saída

- Testes de isolamento entre tenants cobrem todo caminho público de recurso.
- Vetores fixos de avaliação produzem resultados estáveis.
- O rollout percentual demonstra distribuição aproximada sobre uma amostra documentada.
- O playground explica a regra vencedora e o bucket.

## M2 — Publicação Segura

### Resultado

Os times editam rascunhos e publicam revisões imutáveis completas, sem sobrescritas concorrentes silenciosas.

### Escopo

- Representações de rascunho e de publicado.
- Compilação de snapshot completo.
- Concorrência otimista na publicação.
- Validação de regras e de ciclos de pré-requisitos.
- Trilha de auditoria somente-acréscimo.
- Diff, histórico e rollback.
- Política de ambiente protegido e aprovação opcional.

### Critérios de saída

- Tentativas concorrentes de publicação não podem sobrescrever uma à outra silenciosamente.
- Um avaliador nunca observa uma revisão parcial.
- O rollback produz uma nova revisão auditável.
- Falhas de política de produção retornam erros acionáveis.

## M3 — Avaliação Distribuída

### Resultado

Avaliadores replicados servem snapshots versionados de forma eficiente e se recuperam de invalidações perdidas ou falhas temporárias de dependência.

### Escopo

- Cache L1 com Caffeine.
- Cache L2 com Redis e ponteiro de versão.
- Outbox transacional para eventos de versão publicada.
- Invalidação em regime de melhor esforço e reconciliação periódica.
- Coalescência de requisições e proteção contra estouro (stampede).
- Snapshot de último estado bom conhecido, com defasagem limitada.
- SDK Java e provider OpenFeature.
- Telemetria de avaliação e de propagação.

### Critérios de saída

- A perda do Redis tem um modo de degradação documentado e testado.
- Uma invalidação descartada de propósito é reparada pela reconciliação.
- Eventos duplicados são inofensivos.
- Os benchmarks publicam ambiente, dataset, comandos e resultados por percentil.
- O SDK Java passa nos vetores compartilhados de conformidade de avaliação.

## M4 — Entrega Progressiva

### Resultado

Um operador consegue agendar, observar, pausar, retomar e reverter um rollout em etapas.

### Escopo

- Planos de rollout versionados e suas etapas.
- Progressão manual e agendada.
- Pausar, retomar, cancelar e reverter.
- Contrato plugável de health gate.
- Dashboard de rollout ao vivo.
- Eventos de exposição com controles de privacidade.
- Propriedade, expiração e lembretes de limpeza de flags.
- Demo de portfólio fim a fim e revisão de arquitetura.

### Critérios de saída

- Uma demo de checkout avança por pelo menos três etapas percentuais.
- Um health gate reprovado pausa a progressão e preserva a trilha de auditoria.
- Um rollback de emergência muda o comportamento efetivo dentro de um intervalo medido.
- A demo pode ser reproduzida localmente a partir de comandos documentados.

## Ideias adiadas

- SDK TypeScript.
- Configuração como código e validação em pull request.
- Avaliação multirregião.
- Estatística completa de experimentação.
- Integrações com provedores externos de observabilidade.
- Federação de identidade enterprise e SCIM.

O trabalho adiado não faz parte dos critérios iniciais de conclusão do portfólio.

</details>
