# Optimistic publication and graph validation

FlagForge protects the current environment configuration with an explicit,
monotonic publication version. The version is independent from mutable draft
rows and represents the complete published state observed by an operator.

## Publication version contract

- Version `0` means that the environment has never published a revision.
- The first successful publication creates revision `1` and publication version
  `1`.
- Every later successful publication increments both values by one.
- A publication request must include the version observed by its caller as
  `expectedVersion`.
- Negative versions and missing versions are invalid requests.

The current version can be read through:

```http
GET /api/v1/environments/{environmentId}/publication
```

A publication is requested through:

```http
POST /api/v1/environments/{environmentId}/publication
Content-Type: application/json

{"expectedVersion": 3}
```

## PostgreSQL concurrency boundary

Publication does not use a Redis or distributed application lock.

Inside one PostgreSQL transaction, the service:

1. derives the organization from the authenticated principal;
2. resolves and locks the tenant-owned environment row;
3. reads the current publication pointer;
4. compares the stored version with `expectedVersion`;
5. compiles and validates the complete candidate configuration;
6. serializes the immutable snapshot;
7. inserts revision, snapshot, audit, and outbox records;
8. moves the current pointer with a compare-and-set predicate.

The environment row lock serializes writers for the same environment. The
compare-and-set predicate is a second database-level guard and requires the
stored pointer version to equal `expectedVersion`.

Two requests that start from the same version therefore produce exactly one
successful publication. The other request observes the committed version and
fails with an explicit conflict. The failed transaction creates no immutable
history or outbox event.

## Conflict response

A stale publication returns HTTP `409` with type:

```text
urn:flagforge:problem:publication-version-conflict
```

The Problem Details body includes:

- `expectedVersion` supplied by the caller;
- `currentVersion` stored for the environment;
- current revision ID and revision number, when published;
- current snapshot checksum;
- pointer update timestamp;
- correlation ID.

This metadata is sufficient for a client to reload the current state, compare
it with its draft, and decide whether to retry. The server never silently
replaces a newer publication.

## Deterministic graph validation

The targeting candidate is canonicalized before validation:

- flags are ordered by stable flag key;
- prerequisites are ordered by referenced flag and expected variant;
- rules are ordered by priority and stable rule key;
- segments are ordered by stable segment key;
- condition ordering is deterministic.

The existing targeting engine then validates the complete graph, including:

- unknown flag prerequisites;
- duplicate prerequisites;
- cyclic prerequisite graphs;
- unknown segment references;
- cyclic segment references;
- unknown variants;
- duplicate priorities and keys;
- configured graph and depth bounds.

Stable validation codes such as `CYCLIC_PREREQUISITE`, `UNKNOWN_SEGMENT`, and
`UNKNOWN_VARIANT` are propagated to publication Problem Details.

## Snapshot schema compatibility

Snapshot schema version 1 remains readable and retains its fixed 244-byte
compatibility vector and SHA-256 checksum. It contains flags and typed variants,
so decoding it produces an equivalent graph with no prerequisites, rules, or
segments.

New publications use snapshot schema version 2. The same bounded checksummed
binary payload now also contains the canonical targeting graph:

- flag prerequisites and expected variants;
- ordered targeting rules and their typed conditions;
- reusable segments, inclusions, exclusions, and nested references;
- string, number, and boolean comparison values.

The evaluator does not reconstruct this graph from mutable rows. It uses the
graph decoded from the exact current revision payload, so validation,
publication, checksum verification, and runtime evaluation all refer to the
same immutable candidate.

## Tenant and environment scope

The publication service never accepts an organization ID from the caller. It
uses the authenticated tenant identity and resolves the environment through
the tenant hierarchy service.

Active flags and variants are compiled only from the organization and project
owned by that environment. The targeting graph must contain exactly those
active flags and exactly their declared variants and defaults. A graph cannot
introduce a flag or variant from another tenant, project, or environment.
Segments are part of the same complete candidate, and every segment reference
must resolve inside that candidate.

## Atomic failure behavior

Version conflicts and graph validation failures occur before a publication can
become effective. Transactional tests assert that failed attempts leave all of
the following unchanged:

- current publication pointer;
- immutable revision history;
- serialized snapshot history;
- append-only publication audit events;
- transactional outbox records;
- evaluator-visible configuration.

## Verification

The automated suite includes:

- first publication from version `0`;
- monotonic republishing;
- stale sequential publication conflict;
- two concurrent PostgreSQL publications from the same version;
- conflict metadata through HTTP Problem Details;
- missing and negative expected versions;
- cyclic prerequisite and unknown segment validation;
- flag and variant scope validation;
- failed graph publication atomicity;
- schema v1 compatibility and deterministic schema v2 graph round-trip;
- evaluator execution from the graph decoded from the published payload;
- Flyway migration from V1 through V6.

---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# Publicação otimista e validação de grafo

O FlagForge protege a configuração atual do ambiente com uma versão de publicação
explícita e monotônica. A versão é independente das linhas mutáveis de rascunho e
representa o estado publicado completo observado por um operador.

## Contrato da versão de publicação

- A versão `0` significa que o ambiente nunca publicou uma revisão.
- A primeira publicação bem-sucedida cria a revisão `1` e a versão de publicação
  `1`.
- Toda publicação bem-sucedida posterior incrementa ambos os valores em um.
- Uma requisição de publicação precisa incluir a versão observada por quem chama,
  no campo `expectedVersion`.
- Versões negativas e versões ausentes são requisições inválidas.

A versão atual pode ser lida por:

```http
GET /api/v1/environments/{environmentId}/publication
```

Uma publicação é solicitada por:

```http
POST /api/v1/environments/{environmentId}/publication
Content-Type: application/json

{"expectedVersion": 3}
```

## Fronteira de concorrência no PostgreSQL

A publicação não usa Redis nem lock distribuído de aplicação.

Dentro de uma única transação PostgreSQL, o serviço:

1. deriva a organização do principal autenticado;
2. resolve e trava a linha do ambiente pertencente ao tenant;
3. lê o ponteiro de publicação atual;
4. compara a versão armazenada com `expectedVersion`;
5. compila e valida a configuração candidata completa;
6. serializa o snapshot imutável;
7. insere os registros de revisão, snapshot, auditoria e outbox;
8. move o ponteiro atual com um predicado de compare-and-set.

O lock da linha do ambiente serializa os escritores do mesmo ambiente. O predicado
de compare-and-set é uma segunda guarda em nível de banco de dados e exige que a
versão do ponteiro armazenado seja igual a `expectedVersion`.

Duas requisições que partem da mesma versão, portanto, produzem exatamente uma
publicação bem-sucedida. A outra requisição observa a versão confirmada e falha com
um conflito explícito. A transação que falhou não cria histórico imutável nem evento
de outbox.

## Resposta de conflito

Uma publicação defasada retorna HTTP `409` com o tipo:

```text
urn:flagforge:problem:publication-version-conflict
```

O corpo Problem Details inclui:

- `expectedVersion` fornecido por quem chamou;
- `currentVersion` armazenado para o ambiente;
- ID e número da revisão atual, quando publicada;
- checksum do snapshot atual;
- timestamp da atualização do ponteiro;
- ID de correlação.

Esses metadados são suficientes para que um cliente recarregue o estado atual,
compare-o com seu rascunho e decida se vai tentar novamente. O servidor nunca
substitui silenciosamente uma publicação mais nova.

## Validação determinística de grafo

O candidato de segmentação é canonicalizado antes da validação:

- as flags são ordenadas pela chave estável da flag;
- os pré-requisitos são ordenados pela flag referenciada e pela variante esperada;
- as regras são ordenadas por prioridade e pela chave estável da regra;
- os segmentos são ordenados pela chave estável do segmento;
- a ordenação das condições é determinística.

Em seguida, o motor de segmentação existente valida o grafo completo, incluindo:

- pré-requisitos com flags desconhecidas;
- pré-requisitos duplicados;
- grafos de pré-requisitos cíclicos;
- referências a segmentos desconhecidos;
- referências cíclicas de segmentos;
- variantes desconhecidas;
- prioridades e chaves duplicadas;
- limites configurados de grafo e de profundidade.

Códigos estáveis de validação, como `CYCLIC_PREREQUISITE`, `UNKNOWN_SEGMENT` e
`UNKNOWN_VARIANT`, são propagados para o Problem Details da publicação.

## Compatibilidade do schema de snapshot

A versão 1 do schema de snapshot permanece legível e mantém seu vetor fixo de
compatibilidade de 244 bytes e o checksum SHA-256. Ela contém flags e variantes
tipadas, de modo que decodificá-la produz um grafo equivalente sem pré-requisitos,
regras ou segmentos.

Novas publicações usam a versão 2 do schema de snapshot. O mesmo payload binário
limitado e com checksum agora contém também o grafo canônico de segmentação:

- pré-requisitos de flag e variantes esperadas;
- regras ordenadas de segmentação e suas condições tipadas;
- segmentos reutilizáveis, inclusões, exclusões e referências aninhadas;
- valores de comparação string, numéricos e booleanos.

O avaliador não reconstrói esse grafo a partir de linhas mutáveis. Ele usa o grafo
decodificado do payload exato da revisão atual, de modo que validação, publicação,
verificação de checksum e avaliação em runtime se refiram todos ao mesmo candidato
imutável.

## Escopo de tenant e ambiente

O serviço de publicação nunca aceita um ID de organização vindo de quem chama. Ele
usa a identidade de tenant autenticada e resolve o ambiente pelo serviço de hierarquia
de tenants.

Flags e variantes ativas são compiladas apenas a partir da organização e do projeto
donos daquele ambiente. O grafo de segmentação precisa conter exatamente essas flags
ativas e exatamente suas variantes e padrões declarados. Um grafo não pode introduzir
uma flag ou variante de outro tenant, projeto ou ambiente. Os segmentos fazem parte do
mesmo candidato completo, e toda referência de segmento precisa resolver dentro desse
candidato.

## Comportamento atômico em caso de falha

Conflitos de versão e falhas de validação de grafo ocorrem antes que uma publicação
possa se tornar efetiva. Testes transacionais asseguram que tentativas malsucedidas
deixam inalterados todos os itens a seguir:

- ponteiro de publicação atual;
- histórico imutável de revisões;
- histórico de snapshots serializados;
- eventos de auditoria de publicação somente-acréscimo;
- registros do outbox transacional;
- configuração visível ao avaliador.

## Verificação

A suíte automatizada inclui:

- primeira publicação a partir da versão `0`;
- republicação monotônica;
- conflito de publicação sequencial defasada;
- duas publicações PostgreSQL concorrentes a partir da mesma versão;
- metadados de conflito via HTTP Problem Details;
- versões esperadas ausentes e negativas;
- validação de pré-requisito cíclico e de segmento desconhecido;
- validação de escopo de flag e variante;
- atomicidade de publicação com grafo reprovado;
- compatibilidade do schema v1 e round-trip determinístico do grafo do schema v2;
- execução do avaliador a partir do grafo decodificado do payload publicado;
- migração Flyway da V1 até a V6.

</details>
