# Immutable configuration publication

FlagForge separates editable Control Plane state from runtime evaluation state.
Feature flag rows and variants are drafts until an environment publication
successfully creates a complete immutable revision.

## Runtime invariant

An evaluator never assembles configuration from mutable rows. It resolves the
single current revision pointer for the authenticated environment, loads that
revision's exact payload, verifies its checksum and identity, decodes it once,
and evaluates against that complete document.

There is no fallback from a missing or invalid published snapshot to live flag
tables. This prevents mixed-version decisions and makes publication the only
boundary that changes runtime behavior.

## Transaction boundary

`PublicationService.publish(environmentId, expectedVersion)` performs the
following work inside one PostgreSQL transaction:

1. Authorize environment write access and lock the tenant-owned environment.
2. Allocate the next monotonically increasing environment revision.
3. Load and validate the complete active project configuration.
4. Produce a canonical bounded snapshot and SHA-256 checksum.
5. Insert immutable revision metadata.
6. Insert the exact serialized snapshot bytes.
7. Append an audit event.
8. Insert a pending transactional outbox event.
9. Move the environment's current revision pointer.

The pointer is written last for clarity, but PostgreSQL atomicity is the actual
correctness guarantee. Any exception rolls back every write, leaving the prior
published revision effective.

## Identity

A published configuration is identified by all of the following fields:

- organization ID;
- project ID;
- environment ID;
- revision ID and monotonically increasing revision number;
- snapshot schema version;
- evaluation algorithm version;
- SHA-256 checksum of the exact payload bytes.

Foreign keys include the tenant, project, environment, revision ID, and revision
number. A valid pointer therefore cannot reference a revision from another
tenant or environment.

## Canonical snapshot format

Snapshots use a deterministic binary encoding rather than runtime-dependent
object serialization. The common wire format includes:

- the `FFSNAP01` magic header;
- schema version;
- tenant, project, and environment UUIDs;
- revision number;
- algorithm version;
- sorted feature flags;
- sorted typed variants and their values.

Schema version 1 remains readable and preserves its fixed compatibility vector.
Schema version 2 appends the canonical targeting graph to the same payload,
including prerequisites, ordered rules, typed conditions, and reusable
segments. Runtime evaluation consumes this decoded graph directly rather than
reconstructing it from mutable tables.

The current schema supports BOOLEAN and STRING flag values. Keys and strings
use explicit UTF-8 byte limits. Counts are bounded, trailing bytes are rejected,
and the complete payload cannot exceed 1 MiB.

Compatibility tests pin the version 1 byte length and checksum and verify a
deterministic version 2 graph round-trip. Accidental wire-format changes
therefore fail verification instead of silently producing incompatible runtime
data.

## Draft behavior

Changing, adding, or archiving editable flags does not mutate an existing
published payload. Runtime evaluation continues to use the previous revision
until the full candidate configuration validates and a new transaction commits.

An invalid candidate, unsupported type, missing default variant, invalid value,
oversized payload, database error, or serialization error leaves the current
pointer unchanged.

## Immutability controls

Application code exposes creation and read use cases for published revisions,
but no update or delete use cases. PostgreSQL triggers additionally reject
`UPDATE` and `DELETE` operations against:

- `configuration_revisions`;
- `configuration_snapshots`;
- `publication_audit_events`.

Published history also avoids cascading deletion from mutable tenant resources.
The outbox row is intentionally excluded from the immutable trigger because its
delivery status must advance through the relay lifecycle.

## Outbox scope

Publication records one `CONFIGURATION_PUBLISHED` event in the same transaction as the
revision and pointer. The `distribution` module owns that outbox; publication reaches it
through `ConfigurationEventOutbox`, whose `MANDATORY` propagation makes recording an event
outside the publishing transaction fail rather than succeed silently (ADR 0008).

A relay claims events with `FOR UPDATE SKIP LOCKED`, delivers them to in-process
listeners, and marks them `DELIVERED`. Delivery is at least once, so listeners must be
idempotent and must compare versions instead of trusting arrival order. A listener failure
returns the event to `PENDING` with an incremented attempt count and an exponential
backoff; after the configured maximum it becomes `FAILED` and is no longer claimed.

Distribution failures never reach publication: a revision, its snapshot, and the current
pointer are already committed and stay untouched no matter how delivery goes.

## Concurrency scope

The environment row is locked while allocating and committing a revision, and
every publication supplies the last observed environment publication version.
A compare-and-set pointer update requires that stored version to match. Two
writers using the same expected version therefore produce one successful
publication and one explicit conflict without a distributed Redis lock.

## Verification coverage

Automated tests verify:

- revision, snapshot, pointer, audit, and outbox persistence;
- draft edits remain invisible before republishing;
- failed publication leaves the prior revision effective;
- revision history rejects mutation and deletion;
- viewers cannot publish;
- checksum tampering and unsupported schema rejection;
- deterministic schema v1 compatibility and schema v2 graph round-trip;
- PostgreSQL optimistic-concurrency conflicts and metadata;
- evaluation API fixtures publish before runtime evaluation;
- runtime evaluation uses the graph decoded from the immutable payload.

---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# Publicação de configuração imutável

O FlagForge separa o estado editável do Plano de Controle do estado de avaliação
em tempo de execução. As linhas de feature flag e suas variantes são rascunhos até
que uma publicação de ambiente crie com sucesso uma revisão imutável completa.

## Invariante de runtime

Um avaliador nunca monta configuração a partir de linhas mutáveis. Ele resolve o
único ponteiro de revisão atual do ambiente autenticado, carrega o payload exato
dessa revisão, verifica seu checksum e sua identidade, decodifica uma única vez e
avalia contra esse documento completo.

Não existe fallback de um snapshot publicado ausente ou inválido para as tabelas
vivas de flags. Isso evita decisões com versões misturadas e faz da publicação a
única fronteira que muda o comportamento em tempo de execução.

## Fronteira transacional

`PublicationService.publish(environmentId, expectedVersion)` executa o seguinte
trabalho dentro de uma única transação PostgreSQL:

1. Autorizar o acesso de escrita ao ambiente e travar o ambiente pertencente ao tenant.
2. Alocar a próxima revisão do ambiente, monotonicamente crescente.
3. Carregar e validar a configuração ativa completa do projeto.
4. Produzir um snapshot canônico limitado e o checksum SHA-256.
5. Inserir os metadados imutáveis da revisão.
6. Inserir os bytes serializados exatos do snapshot.
7. Acrescentar um evento de auditoria.
8. Inserir um evento pendente no outbox transacional.
9. Mover o ponteiro de revisão atual do ambiente.

O ponteiro é escrito por último por clareza, mas a atomicidade do PostgreSQL é a
garantia real de correção. Qualquer exceção reverte todas as escritas, deixando
efetiva a revisão publicada anterior.

## Identidade

Uma configuração publicada é identificada por todos os campos a seguir:

- ID da organização;
- ID do projeto;
- ID do ambiente;
- ID da revisão e número de revisão monotonicamente crescente;
- versão do schema do snapshot;
- versão do algoritmo de avaliação;
- checksum SHA-256 dos bytes exatos do payload.

As chaves estrangeiras incluem tenant, projeto, ambiente, ID da revisão e número
da revisão. Um ponteiro válido, portanto, não pode referenciar uma revisão de
outro tenant ou ambiente.

## Formato canônico do snapshot

Os snapshots usam uma codificação binária determinística, em vez de serialização
de objetos dependente do runtime. O formato de fio comum inclui:

- o cabeçalho mágico `FFSNAP01`;
- a versão do schema;
- os UUIDs de tenant, projeto e ambiente;
- o número da revisão;
- a versão do algoritmo;
- as feature flags ordenadas;
- as variantes tipadas ordenadas e seus valores.

A versão 1 do schema permanece legível e preserva seu vetor fixo de compatibilidade.
A versão 2 do schema acrescenta o grafo canônico de segmentação ao mesmo payload,
incluindo pré-requisitos, regras ordenadas, condições tipadas e segmentos
reutilizáveis. A avaliação em tempo de execução consome esse grafo decodificado
diretamente, em vez de reconstruí-lo a partir de tabelas mutáveis.

O schema atual suporta valores de flag BOOLEAN e STRING. Chaves e strings usam
limites explícitos de bytes UTF-8. As contagens são limitadas, bytes residuais são
rejeitados e o payload completo não pode exceder 1 MiB.

Os testes de compatibilidade fixam o comprimento em bytes e o checksum da versão 1
e verificam um round-trip determinístico do grafo da versão 2. Mudanças acidentais
de formato de fio, portanto, falham na verificação em vez de produzir silenciosamente
dados incompatíveis em runtime.

## Comportamento de rascunho

Alterar, adicionar ou arquivar flags editáveis não altera um payload publicado
existente. A avaliação em tempo de execução continua usando a revisão anterior até
que a configuração candidata completa seja validada e uma nova transação seja
confirmada.

Um candidato inválido, tipo não suportado, variante padrão ausente, valor inválido,
payload acima do limite, erro de banco de dados ou erro de serialização deixa o
ponteiro atual inalterado.

## Controles de imutabilidade

O código da aplicação expõe casos de uso de criação e leitura de revisões publicadas,
mas nenhum caso de uso de atualização ou exclusão. Além disso, triggers do PostgreSQL
rejeitam operações de `UPDATE` e `DELETE` contra:

- `configuration_revisions`;
- `configuration_snapshots`;
- `publication_audit_events`.

O histórico publicado também evita exclusão em cascata a partir de recursos mutáveis
do tenant. A linha do outbox é intencionalmente excluída do trigger de imutabilidade,
porque seu status de entrega precisa avançar pelo ciclo de vida do relay.

## Escopo do outbox

A publicação registra um evento `CONFIGURATION_PUBLISHED` na mesma transação da revisão e
do ponteiro. O módulo `distribution` possui esse outbox; a publicação chega até ele por
`ConfigurationEventOutbox`, cuja propagação `MANDATORY` faz com que registrar um evento
fora da transação de publicação falhe, em vez de suceder silenciosamente (ADR 0008).

Um relay reivindica eventos com `FOR UPDATE SKIP LOCKED`, entrega-os a listeners em
processo e os marca como `DELIVERED`. A entrega é ao menos uma vez, então os listeners
precisam ser idempotentes e comparar versões, em vez de confiar na ordem de chegada. Uma
falha de listener devolve o evento a `PENDING`, com a contagem de tentativas incrementada e
backoff exponencial; após o máximo configurado, ele vira `FAILED` e deixa de ser reivindicado.

Falhas de distribuição nunca alcançam a publicação: a revisão, seu snapshot e o ponteiro
atual já estão confirmados e permanecem intactos, independentemente do que aconteça na entrega.

## Escopo de concorrência

A linha do ambiente é travada enquanto uma revisão é alocada e confirmada, e toda
publicação fornece a última versão de publicação do ambiente observada. Uma
atualização de ponteiro por compare-and-set exige que a versão armazenada coincida.
Dois escritores usando a mesma versão esperada, portanto, produzem uma publicação
bem-sucedida e um conflito explícito, sem um lock distribuído no Redis.

## Cobertura de verificação

Os testes automatizados verificam:

- a persistência de revisão, snapshot, ponteiro, auditoria e outbox;
- que edições de rascunho permanecem invisíveis antes de uma nova publicação;
- que uma publicação falha deixa a revisão anterior efetiva;
- que o histórico de revisões rejeita mutação e exclusão;
- que viewers não podem publicar;
- a rejeição de checksum adulterado e de schema não suportado;
- a compatibilidade determinística do schema v1 e o round-trip do grafo do schema v2;
- os conflitos de concorrência otimista do PostgreSQL e seus metadados;
- que as fixtures da API de avaliação publicam antes da avaliação em runtime;
- que a avaliação em runtime usa o grafo decodificado do payload imutável.

</details>
