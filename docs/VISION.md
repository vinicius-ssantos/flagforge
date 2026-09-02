# Product Vision

## Vision

FlagForge gives software teams runtime control over how changes reach customers. It turns a risky all-at-once release into a controlled, observable, and reversible process.

## Problem statement

Teams that deploy frequently need to answer questions that deployment tooling alone does not solve:

- Can this code be deployed without immediately exposing it?
- Can the feature be enabled for internal users or one customer first?
- Can exposure increase gradually while behavior is monitored?
- Can an operator stop the change without waiting for a rebuild and redeploy?
- Can the team prove who changed production behavior and why?
- Can support explain why one customer received a different result?

Home-grown flags often begin as environment variables or database booleans. They become risky when configuration has no ownership, history, deterministic targeting, tenant isolation, or reliable propagation.

## Ideal customer profile

The initial ideal user is a small or medium-sized SaaS engineering organization that:

- Operates multiple environments.
- Deploys at least several times per week.
- Serves multiple customer organizations or user segments.
- Already uses ad-hoc flags or remote configuration.
- Needs better auditability but finds enterprise platforms too expensive or complex.
- Builds primarily on Java/Spring or wants vendor-neutral OpenFeature integration.

## Personas and jobs to be done

| Persona | Job to be done |
|---|---|
| Application developer | Merge and deploy code safely before exposing it |
| Platform engineer | Provide a reliable, standardized flag service to many teams |
| SRE/operator | Limit blast radius and disable dangerous behavior quickly |
| QA engineer | Activate hidden behavior for controlled validation |
| Product manager | Coordinate staged availability without scheduling deployments |
| Support engineer | Explain the effective configuration for a specific customer |
| Security/auditor | Verify who changed production behavior and whether it was approved |

## Value proposition

For software teams that release frequently, FlagForge is a progressive delivery platform that makes runtime changes gradual, explainable, and reversible. Unlike ad-hoc flags, it provides deterministic evaluation, immutable versions, tenant-aware governance, and a vendor-neutral integration path.

## Differentiation hypothesis

FlagForge will not compete by claiming the largest feature list. Its portfolio and product differentiation are:

- OpenFeature-native integration rather than a proprietary-only SDK.
- Java/Spring-first developer experience.
- An Evaluation Playground that exposes the complete decision trace.
- SaaS and self-hosted-friendly architecture.
- Governance features that remain understandable for smaller teams.
- Explicit consistency, staleness, and failure semantics.

## Success measures

Measurements are defined with a reproducible environment before numeric targets are committed. The platform should track:

- Evaluation latency percentiles.
- Cache hit ratios per layer.
- Configuration propagation delay.
- Stale or fallback evaluations.
- Rollout pause and rollback actions.
- Flag age and overdue cleanup.
- Publication failures and optimistic concurrency conflicts.

## Non-goals

The initial product will not:

- Replace authentication, authorization, entitlements, or permanent business rules.
- Provide a complete statistical experimentation platform.
- Support every SDK ecosystem at launch.
- Guarantee globally strong consistency at arbitrary scale.
- Store authoritative configuration only in Redis.
- Begin as a fleet of microservices.
- Use machine learning to decide rollout allocation.

## Product risks

| Risk | Mitigation |
|---|---|
| Becoming a CRUD clone | Prioritize evaluation semantics, publishing safety, and failure behavior |
| Flag debt | Ownership, expiration, archive workflows, and age metrics |
| Tenant data exposure | Auth-derived tenant context, constraints, authorization, and adversarial tests |
| Stale configuration | Versioned snapshots, bounded staleness, invalidation plus reconciliation |
| Overengineering | Deliver vertical slices and require evidence before extracting services |
| Vendor lock-in | OpenFeature provider and exportable configuration model |


---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# Visão de Produto

## Visão

O FlagForge dá aos times de software controle em tempo de execução sobre como as mudanças chegam aos clientes. Ele transforma um lançamento arriscado de tudo de uma vez em um processo controlado, observável e reversível.

## Enunciado do problema

Times que fazem deploy com frequência precisam responder perguntas que o ferramental de deploy sozinho não resolve:

- Este código pode ser implantado sem ser exposto imediatamente?
- A funcionalidade pode ser habilitada primeiro para usuários internos ou um único cliente?
- A exposição pode aumentar gradualmente enquanto o comportamento é monitorado?
- Um operador consegue interromper a mudança sem esperar por um novo build e deploy?
- O time consegue comprovar quem alterou o comportamento em produção e por quê?
- O suporte consegue explicar por que um cliente recebeu um resultado diferente?

Flags caseiras costumam começar como variáveis de ambiente ou booleanos no banco de dados. Elas se tornam arriscadas quando a configuração não tem dono, histórico, segmentação determinística, isolamento entre tenants ou propagação confiável.

## Perfil de cliente ideal

O usuário ideal inicial é uma organização de engenharia SaaS de pequeno ou médio porte que:

- Opera múltiplos ambientes.
- Faz deploy pelo menos algumas vezes por semana.
- Atende múltiplas organizações clientes ou segmentos de usuários.
- Já usa flags improvisadas ou configuração remota.
- Precisa de melhor auditabilidade, mas considera plataformas enterprise caras ou complexas demais.
- Constrói principalmente sobre Java/Spring ou quer integração neutra de fornecedor via OpenFeature.

## Personas e trabalhos a serem feitos

| Persona | Trabalho a ser feito |
|---|---|
| Pessoa desenvolvedora de aplicação | Fazer merge e deploy do código com segurança antes de expô-lo |
| Pessoa engenheira de plataforma | Oferecer um serviço de flags confiável e padronizado para vários times |
| SRE/operação | Limitar o raio de impacto e desativar comportamento perigoso rapidamente |
| Pessoa de QA | Ativar comportamento oculto para validação controlada |
| Pessoa de produto | Coordenar disponibilidade em etapas sem agendar deploys |
| Pessoa de suporte | Explicar a configuração efetiva para um cliente específico |
| Segurança/auditoria | Verificar quem alterou o comportamento em produção e se houve aprovação |

## Proposta de valor

Para times de software que lançam com frequência, o FlagForge é uma plataforma de entrega progressiva que torna mudanças em tempo de execução graduais, explicáveis e reversíveis. Diferentemente de flags improvisadas, ele oferece avaliação determinística, versões imutáveis, governança ciente de tenants e um caminho de integração neutro de fornecedor.

## Hipótese de diferenciação

O FlagForge não vai competir alegando a maior lista de funcionalidades. Sua diferenciação de portfólio e de produto é:

- Integração nativa com OpenFeature, em vez de um SDK exclusivamente proprietário.
- Experiência de desenvolvimento com Java/Spring em primeiro lugar.
- Um Evaluation Playground que expõe o rastro completo da decisão.
- Arquitetura amigável tanto a SaaS quanto a self-hosted.
- Recursos de governança que permanecem compreensíveis para times menores.
- Semântica explícita de consistência, defasagem e falha.

## Medidas de sucesso

As medições são definidas com um ambiente reprodutível antes que metas numéricas sejam assumidas. A plataforma deve acompanhar:

- Percentis de latência de avaliação.
- Taxas de acerto de cache por camada.
- Atraso de propagação de configuração.
- Avaliações defasadas ou em fallback.
- Ações de pausa e rollback de rollout.
- Idade das flags e limpeza atrasada.
- Falhas de publicação e conflitos de concorrência otimista.

## Não objetivos

O produto inicial não vai:

- Substituir autenticação, autorização, entitlements ou regras de negócio permanentes.
- Fornecer uma plataforma completa de experimentação estatística.
- Suportar todos os ecossistemas de SDK no lançamento.
- Garantir consistência forte global em escala arbitrária.
- Armazenar a configuração autoritativa apenas no Redis.
- Começar como uma frota de microsserviços.
- Usar aprendizado de máquina para decidir a alocação de rollout.

## Riscos de produto

| Risco | Mitigação |
|---|---|
| Virar um clone de CRUD | Priorizar semântica de avaliação, segurança de publicação e comportamento de falha |
| Dívida de flags | Propriedade, expiração, fluxos de arquivamento e métricas de idade |
| Exposição de dados entre tenants | Contexto de tenant derivado da autenticação, constraints, autorização e testes adversariais |
| Configuração defasada | Snapshots versionados, defasagem limitada, invalidação somada à reconciliação |
| Excesso de engenharia | Entregar fatias verticais e exigir evidência antes de extrair serviços |
| Aprisionamento a fornecedor | Provider OpenFeature e modelo de configuração exportável |

</details>
