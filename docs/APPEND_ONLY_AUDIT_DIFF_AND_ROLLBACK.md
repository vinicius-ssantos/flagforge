# Append-only audit, revision diff, and rollback

FlagForge treats every published configuration as immutable evidence. Audit
history, revision comparison, and rollback operate on that evidence without
rewriting or deleting a prior state.

## Append-only audit trail

The `audit_events` table records bounded action metadata for control-plane
changes. Each event contains:

- organization, optional project, and optional environment scope;
- actor identifier;
- stable action and resource type;
- resource identifier;
- optional immutable revision identity and number;
- correlation identifier;
- bounded JSON details;
- occurrence timestamp.

PostgreSQL rejects `UPDATE` and `DELETE` operations through the same immutable
mutation guard used by published revision history. Application services expose
only append and read operations.

Audit insertion uses transaction propagation `MANDATORY`. A flag, credential,
publication, or rollback event therefore commits or rolls back with the
corresponding state change. A failed publication cannot leave an audit event,
outbox event, snapshot, or partial revision behind.

## Sensitive-data boundary

Audit details accept only a small, bounded map of text metadata. Keys suggesting
secrets, passwords, plaintext credentials, tokens, targeting keys, evaluation
contexts, or user attributes are rejected before persistence.

Credential events contain identifiers, scope, and status transitions. They do
not contain:

- SDK credential plaintext;
- persisted secret hashes;
- key prefixes;
- credential names;
- request evaluation contexts.

Configuration audit events contain revision provenance and checksums, not a
copy of the complete serialized payload.

## Audited actions

The current slice records:

- feature-flag creation and archival;
- SDK credential creation, rotation, and revocation;
- normal configuration publication;
- configuration rollback.

The schema reserves stable actions for the review lifecycle introduced by issue
#17: change-request creation, submission, approval, rejection, and publication.
This issue does not claim that workflow is already implemented.

## Authorization

Audit and revision history require the dedicated `AUDIT_READ` permission.

- OWNER, ADMIN, and VIEWER can inspect audit and revision history.
- DEVELOPER cannot inspect the audit trail.
- Rollback continues to require `ENVIRONMENT_WRITE`.

Tenant identity comes from the authenticated principal. Organization IDs are
not accepted from request bodies, and every environment and revision lookup is
scoped by organization, project, and environment.

## Revision history

Every revision summary exposes:

- revision ID and monotonically increasing number;
- revision kind: `PUBLISH` or `ROLLBACK`;
- rollback source revision, when applicable;
- schema and evaluation algorithm versions;
- checksum and payload size;
- publishing actor, correlation ID, and timestamp;
- whether the revision is currently effective.

History is ordered newest first and reads immutable snapshot metadata only.

## Deterministic configuration diff

Comparison loads two exact immutable payloads, verifies their checksums and
identities, and flattens them into stable configuration paths. The diff includes:

- flag enabled state, type, and default variant;
- typed variants and values;
- prerequisites;
- targeting rules, priorities, variants, and conditions;
- segment inclusions, exclusions, and conditions.

Paths are sorted, condition representations are canonical, and every difference
is classified as `ADDED`, `REMOVED`, or `CHANGED`. The diff never consults
mutable draft tables.

## Rollback invariant

Rollback never points directly to an older revision and never edits history.
It performs a new publication transaction:

1. authorize environment write access;
2. lock the environment and verify `expectedVersion`;
3. load the selected older snapshot within the same tenant environment;
4. verify checksum and embedded identity;
5. construct a new snapshot with a new revision number;
6. insert a new revision marked `ROLLBACK` and link its source revision;
7. insert snapshot, publication evidence, generalized audit, and outbox rows;
8. advance the current pointer with compare-and-set semantics.

The restored behavior can match an earlier revision, but its revision ID,
revision number, checksum, publication version, audit event, and outbox event
are new.

## HTTP API

```http
GET /api/v1/environments/{environmentId}/audit?limit=100
```

```http
GET /api/v1/environments/{environmentId}/revisions?limit=100
```

```http
GET /api/v1/environments/{environmentId}/revisions/diff?fromRevision=1&toRevision=2
```

```http
POST /api/v1/environments/{environmentId}/rollback
Content-Type: application/json

{
  "sourceRevisionNumber": 1,
  "expectedVersion": 2
}
```

A stale rollback receives the same publication-version `409 Conflict` contract
as a normal publication.

## Verification

The integration suite proves:

- V1 through V7 migrate and validate from an empty PostgreSQL database;
- publication and generalized audit persist atomically;
- failed publication leaves pointer, history, audit, snapshot, and outbox counts
  unchanged;
- deterministic diff reports exact before/after values;
- rollback creates a third revision from revision one while preserving revisions
  one and two;
- the evaluator uses the new rollback revision;
- audit rows reject update and deletion;
- audit-read authorization differs from configuration-write authorization;
- credential audit details contain no plaintext, hash, key prefix, or name.

---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# Auditoria somente-acréscimo, diff de revisões e rollback

O FlagForge trata toda configuração publicada como evidência imutável. O histórico
de auditoria, a comparação de revisões e o rollback operam sobre essa evidência sem
reescrever nem excluir um estado anterior.

## Trilha de auditoria somente-acréscimo

A tabela `audit_events` registra metadados limitados de ação para mudanças no plano
de controle. Cada evento contém:

- escopo de organização, projeto opcional e ambiente opcional;
- identificador do ator;
- ação estável e tipo de recurso;
- identificador do recurso;
- identidade e número de revisão imutável opcionais;
- identificador de correlação;
- detalhes JSON limitados;
- timestamp de ocorrência.

O PostgreSQL rejeita operações de `UPDATE` e `DELETE` por meio da mesma guarda de
mutação imutável usada pelo histórico de revisões publicadas. Os serviços da aplicação
expõem apenas operações de acréscimo e de leitura.

A inserção de auditoria usa propagação de transação `MANDATORY`. Um evento de flag,
credencial, publicação ou rollback, portanto, é confirmado ou revertido junto com a
mudança de estado correspondente. Uma publicação malsucedida não pode deixar para trás
um evento de auditoria, evento de outbox, snapshot ou revisão parcial.

## Fronteira de dados sensíveis

Os detalhes de auditoria aceitam apenas um mapa pequeno e limitado de metadados
textuais. Chaves que sugiram segredos, senhas, credenciais em texto plano, tokens,
chaves de segmentação, contextos de avaliação ou atributos de usuário são rejeitadas
antes da persistência.

Eventos de credencial contêm identificadores, escopo e transições de status. Eles não
contêm:

- texto plano de credencial de SDK;
- hashes de segredo persistidos;
- prefixos de chave;
- nomes de credencial;
- contextos de avaliação de requisições.

Eventos de auditoria de configuração contêm a proveniência da revisão e checksums, e
não uma cópia do payload serializado completo.

## Ações auditadas

A fatia atual registra:

- criação e arquivamento de feature flag;
- criação, rotação e revogação de credencial de SDK;
- publicação normal de configuração;
- rollback de configuração.

O schema reserva ações estáveis para o ciclo de vida de revisão introduzido pela issue
#17: criação, submissão, aprovação, rejeição e publicação de solicitação de mudança.
Esta issue não afirma que tal fluxo já esteja implementado.

## Autorização

Auditoria e histórico de revisões exigem a permissão dedicada `AUDIT_READ`.

- OWNER, ADMIN e VIEWER podem inspecionar auditoria e histórico de revisões.
- DEVELOPER não pode inspecionar a trilha de auditoria.
- O rollback continua exigindo `ENVIRONMENT_WRITE`.

A identidade do tenant vem do principal autenticado. IDs de organização não são aceitos
no corpo da requisição, e toda busca de ambiente e de revisão tem escopo de organização,
projeto e ambiente.

## Histórico de revisões

Todo resumo de revisão expõe:

- ID da revisão e número monotonicamente crescente;
- tipo da revisão: `PUBLISH` ou `ROLLBACK`;
- revisão de origem do rollback, quando aplicável;
- versões do schema e do algoritmo de avaliação;
- checksum e tamanho do payload;
- ator que publicou, ID de correlação e timestamp;
- se a revisão está atualmente efetiva.

O histórico é ordenado do mais novo para o mais antigo e lê apenas metadados imutáveis
de snapshot.

## Diff determinístico de configuração

A comparação carrega dois payloads imutáveis exatos, verifica seus checksums e
identidades e os achata em caminhos estáveis de configuração. O diff inclui:

- estado de habilitação, tipo e variante padrão da flag;
- variantes tipadas e valores;
- pré-requisitos;
- regras de segmentação, prioridades, variantes e condições;
- inclusões, exclusões e condições de segmento.

Os caminhos são ordenados, as representações de condição são canônicas e toda diferença
é classificada como `ADDED`, `REMOVED` ou `CHANGED`. O diff nunca consulta tabelas
mutáveis de rascunho.

## Invariante de rollback

O rollback nunca aponta diretamente para uma revisão antiga e nunca edita o histórico.
Ele executa uma nova transação de publicação:

1. autorizar o acesso de escrita ao ambiente;
2. travar o ambiente e verificar `expectedVersion`;
3. carregar o snapshot antigo selecionado dentro do mesmo ambiente do tenant;
4. verificar checksum e identidade embutida;
5. construir um novo snapshot com um novo número de revisão;
6. inserir uma nova revisão marcada como `ROLLBACK` e vincular sua revisão de origem;
7. inserir as linhas de snapshot, evidência de publicação, auditoria generalizada e outbox;
8. avançar o ponteiro atual com semântica de compare-and-set.

O comportamento restaurado pode coincidir com o de uma revisão anterior, mas seu ID de
revisão, número de revisão, checksum, versão de publicação, evento de auditoria e evento
de outbox são novos.

## API HTTP

```http
GET /api/v1/environments/{environmentId}/audit?limit=100
```

```http
GET /api/v1/environments/{environmentId}/revisions?limit=100
```

```http
GET /api/v1/environments/{environmentId}/revisions/diff?fromRevision=1&toRevision=2
```

```http
POST /api/v1/environments/{environmentId}/rollback
Content-Type: application/json

{
  "sourceRevisionNumber": 1,
  "expectedVersion": 2
}
```

Um rollback defasado recebe o mesmo contrato de `409 Conflict` de versão de publicação
que uma publicação normal.

## Verificação

A suíte de integração comprova que:

- as migrações V1 até V7 aplicam e validam a partir de um banco PostgreSQL vazio;
- a publicação e a auditoria generalizada persistem atomicamente;
- uma publicação malsucedida deixa inalteradas as contagens de ponteiro, histórico,
  auditoria, snapshot e outbox;
- o diff determinístico reporta valores exatos de antes/depois;
- o rollback cria uma terceira revisão a partir da revisão um, preservando as revisões
  um e dois;
- o avaliador usa a nova revisão de rollback;
- as linhas de auditoria rejeitam atualização e exclusão;
- a autorização de leitura de auditoria difere da autorização de escrita de configuração;
- os detalhes de auditoria de credencial não contêm texto plano, hash, prefixo de chave
  nem nome.

</details>
