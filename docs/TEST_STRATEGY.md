# Test Strategy

FlagForge prioritizes evidence for domain invariants, tenant isolation, concurrency, and failure recovery over an arbitrary test count or coverage percentage.

## Test layers

| Layer | Purpose |
|---|---|
| Unit | Rule operators, normalization, reason selection, hash/bucket mapping |
| Property-based | Determinism, rollout monotonicity, allocation bounds, parser robustness |
| Module | Application use cases and module contracts with controlled dependencies |
| Architecture | Module cycles, internal package access, dependency policy |
| Integration | PostgreSQL constraints, Flyway migrations, Redis behavior, outbox |
| Concurrency | Competing publications, cache fill coalescing, idempotent delivery |
| Security | Cross-tenant access, role boundaries, key rotation and revocation |
| Contract | OpenAPI and shared evaluator/SDK conformance vectors |
| End-to-end | Console-to-publication-to-SDK user journeys |
| Performance | Reproducible evaluation latency, throughput, cache and propagation tests |

## Mandatory scenarios

### Determinism

- Same snapshot and normalized context return the same result across repeated runs.
- Java SDK and server evaluator pass the same fixed vectors.
- Rollout increases retain subjects that were previously included.
- Unknown or missing attributes follow declared operator semantics.

### Tenant isolation

- Organization A cannot fetch Organization B resources by guessed identifier.
- Organization A cannot reference Organization B segments or prerequisites.
- An SDK key is valid only for its environment and permitted operation.
- Cache keys and lookups include the full tenant boundary.

### Publication and concurrency

- Two writers publishing the same expected version produce one success and one conflict.
- Publication persists revision, pointer, audit, and outbox atomically.
- Invalid prerequisite cycles are rejected before state changes.
- Rollback creates a new version without mutating old content.

### Cache and delivery

- Duplicate version events are idempotent.
- Out-of-order events never replace a newer cached version with an older one.
- A missed invalidation converges through reconciliation.
- Concurrent misses for the same snapshot are coalesced.
- Redis unavailability follows documented fallback behavior.
- Stale snapshots are never served beyond the configured budget.

### Security and privacy

- Stored credentials are hashed and plaintext is shown only at creation.
- Logs and traces do not contain credentials or complete sensitive contexts.
- Metric labels do not contain targeting keys.
- Revoked keys fail subsequent authorization.

## Performance methodology

Every published performance claim includes:

- Commit SHA and build profile.
- Hardware and container limits.
- Java runtime and garbage collector.
- Dataset size and rule complexity.
- Cold and warm cache distinction.
- Request concurrency and duration.
- p50, p95, p99, throughput, and error rate.
- Commands required to reproduce the run.

Targets are set after a baseline exists. Marketing-style latency claims without methodology are not accepted.

## Definition of done

A change is done when relevant invariants and negative cases are tested, public behavior is documented, telemetry exists for operationally significant paths, and tests remain deterministic from a clean environment.


---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# Estratégia de Testes

O FlagForge prioriza evidência para invariantes de domínio, isolamento entre tenants, concorrência e recuperação de falhas em vez de uma contagem arbitrária de testes ou percentual de cobertura.

## Camadas de teste

| Camada | Propósito |
|---|---|
| Unitária | Operadores de regra, normalização, seleção de razão, mapeamento hash/bucket |
| Baseada em propriedades | Determinismo, monotonicidade de rollout, limites de alocação, robustez do parser |
| Módulo | Casos de uso da aplicação e contratos de módulo com dependências controladas |
| Arquitetura | Ciclos entre módulos, acesso a pacote interno, política de dependências |
| Integração | Constraints do PostgreSQL, migrações Flyway, comportamento do Redis, outbox |
| Concorrência | Publicações concorrentes, coalescência de preenchimento de cache, entrega idempotente |
| Segurança | Acesso entre tenants, fronteiras de papéis, rotação e revogação de chaves |
| Contrato | OpenAPI e vetores compartilhados de conformidade avaliador/SDK |
| Fim a fim | Jornadas do usuário do console à publicação e ao SDK |
| Desempenho | Latência de avaliação reprodutível, throughput, testes de cache e de propagação |

## Cenários obrigatórios

### Determinismo

- O mesmo snapshot e o mesmo contexto normalizado retornam o mesmo resultado em execuções repetidas.
- O SDK Java e o avaliador do servidor passam nos mesmos vetores fixos.
- Aumentos de rollout mantêm os sujeitos que já estavam incluídos.
- Atributos desconhecidos ou ausentes seguem a semântica declarada do operador.

### Isolamento entre tenants

- A Organização A não consegue buscar recursos da Organização B adivinhando identificadores.
- A Organização A não consegue referenciar segmentos ou pré-requisitos da Organização B.
- Uma chave de SDK é válida somente para seu ambiente e para a operação permitida.
- Chaves e consultas de cache incluem a fronteira completa do tenant.

### Publicação e concorrência

- Dois escritores publicando a mesma versão esperada produzem um sucesso e um conflito.
- A publicação persiste revisão, ponteiro, auditoria e outbox de forma atômica.
- Ciclos inválidos de pré-requisito são rejeitados antes de qualquer mudança de estado.
- O rollback cria uma nova versão sem mutar conteúdo antigo.

### Cache e distribuição

- Eventos de versão duplicados são idempotentes.
- Eventos fora de ordem nunca substituem uma versão em cache mais nova por uma mais antiga.
- Uma invalidação perdida converge por meio da reconciliação.
- Faltas concorrentes para o mesmo snapshot são coalescidas.
- A indisponibilidade do Redis segue o comportamento de fallback documentado.
- Snapshots defasados nunca são servidos além do orçamento configurado.

### Segurança e privacidade

- Credenciais armazenadas são hasheadas e o texto plano só é exibido na criação.
- Logs e traces não contêm credenciais nem contextos sensíveis completos.
- Rótulos de métrica não contêm chaves de segmentação.
- Chaves revogadas falham nas autorizações subsequentes.

## Metodologia de desempenho

Toda afirmação de desempenho publicada inclui:

- SHA do commit e perfil de build.
- Hardware e limites de contêiner.
- Runtime Java e coletor de lixo.
- Tamanho do dataset e complexidade das regras.
- Distinção entre cache frio e quente.
- Concorrência e duração das requisições.
- p50, p95, p99, throughput e taxa de erro.
- Comandos necessários para reproduzir a execução.

As metas são definidas depois que existe um baseline. Afirmações de latência em estilo de marketing, sem metodologia, não são aceitas.

## Definição de pronto

Uma mudança está pronta quando os invariantes relevantes e os casos negativos estão testados, o comportamento público está documentado, existe telemetria para os caminhos operacionalmente significativos e os testes permanecem determinísticos a partir de um ambiente limpo.

</details>
