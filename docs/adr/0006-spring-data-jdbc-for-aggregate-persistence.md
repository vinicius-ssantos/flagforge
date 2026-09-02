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

---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# ADR 0006: Usar Spring Data JDBC para persistência de agregados

- Status: Aceito
- Data: 2026-07-15

## Contexto

O FlagForge persiste no PostgreSQL agregados com escopo de tenant, revisões publicadas imutáveis, snapshots de avaliação compilados, registros de auditoria, estado de rollout e entradas de outbox transacional.

A abordagem de persistência precisa preservar fronteiras explícitas de agregado e o comportamento transacional. O projeto também precisa de controle direto sobre SQL sensível a concorrência, registros somente-acréscimo, leituras em massa de snapshots e consultas operacionais. A escolha inicial é entre Spring Data JDBC e Spring Data JPA com Hibernate.

## Decisão

Usar Spring Data JDBC como tecnologia padrão de repositório para persistência de agregados.

- Definir um repositório por raiz de agregado, em vez de repositórios para cada tabela.
- Manter referências entre agregados como identificadores ou contratos deliberados em nível de aplicação.
- Usar o Flyway como único mecanismo de criação e migração de schema.
- Usar `JdbcClient`, `JdbcTemplate` ou implementações customizadas de repositório quando uma consulta, lock, operação em massa, carga de snapshot, reivindicação de outbox ou projeção ficar mais clara como SQL explícito.
- Não introduzir entidades JPA, geração de schema pelo Hibernate, proxies de lazy loading ou Open Session in View.
- Manter as fronteiras transacionais nos serviços de aplicação e tornar a concorrência otimista explícita no modelo do banco de dados.

## Fundamentação

O Spring Data JDBC está alinhado ao modelo de domínio orientado a agregados do projeto e mantém o comportamento de persistência visível. Ele evita lazy loading implícito, efeitos colaterais de contexto de persistência e travessia acidental de grafo entre fronteiras de módulo ou de tenant.

A escolha também se encaixa nos caminhos de banco de dados mais importantes do FlagForge:

- inserção de revisões e snapshots imutáveis;
- atualizações explícitas de publicação por compare-and-set;
- escritas somente-acréscimo de auditoria e outbox;
- carga determinística de uma versão completa de snapshot;
- constraints específicas do PostgreSQL e testes de concorrência.

## Consequências

### Positivas

- A propriedade dos agregados e as fronteiras dos repositórios permanecem explícitas.
- O SQL e o comportamento transacional ficam mais fáceis de inspecionar em análises de concorrência e de falha.
- Os objetos de domínio não exigem proxies JPA nem relacionamentos bidirecionais.
- Recursos específicos do PostgreSQL podem ser introduzidos deliberadamente atrás de contratos de repositório.
- A persistência permanece compatível com as fronteiras do monólito modular.

### Negativas

- Modelos de leitura complexos podem exigir SQL e código de mapeamento explícitos.
- O Spring Data JDBC trata entidades alcançáveis a partir de uma raiz de agregado como parte desse agregado e pode excluir e recriar linhas filhas durante atualizações do agregado.
- Coleções mutáveis grandes não devem ser persistidas por substituição ingênua do agregado.
- Não há verificação automática de alterações (dirty checking) nem carga preguiçosa de relacionamentos.

## Guardrails

- Manter os agregados pequenos o bastante para serem atualizados atomicamente.
- Persistir snapshots imutáveis grandes por componentes dedicados de inserção/carga, e não por um save de agregado mutável grande.
- Usar projeções para consultas de console e operacionais com carga de leitura alta.
- Adicionar constraints de banco de dados para todo invariante que possa ser imposto relacionalmente.
- Testar migrações, escopo de tenant, concorrência otimista e rollback de transação contra o PostgreSQL com Testcontainers.

## Alternativas consideradas

### Spring Data JPA e Hibernate

Rejeitada como padrão porque seu contexto de persistência, relacionamentos preguiçosos e mapeamento orientado a grafo podem obscurecer o acesso ao banco e as fronteiras dos agregados. JPA continua viável para aplicações dominadas por grafos relacionais navegáveis, mas essa não é a carga de trabalho principal do FlagForge.

### Apenas Spring JDBC puro

Rejeitada como abordagem única porque o Spring Data JDBC fornece convenções úteis de repositório e de agregado, permitindo ainda assim SQL explícito onde necessário.

### jOOQ

Adiada. Pode ser reconsiderada se o projeto desenvolver um conjunto grande de projeções SQL type-safe complexas cujo valor supere materialmente o do Spring Data JDBC somado ao `JdbcClient`.

## Revisitar quando

Reconsiderar esta decisão se houver evidência executável de que:

- a maior parte do código de persistência vira boilerplate de mapeamento customizado;
- atualizações de agregado não podem ser modeladas com segurança sem SQL manual excessivo;
- consultas de leitura complexas dominam o desenvolvimento e se beneficiariam materialmente de SQL type-safe gerado;
- um caso de uso medido demonstra que o modelo de unidade de trabalho do JPA se encaixa melhor, sem enfraquecer as fronteiras de módulo ou de tenant.

</details>
