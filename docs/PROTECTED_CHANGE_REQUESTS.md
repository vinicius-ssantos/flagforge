# Protected environment change requests

FlagForge can require an explicit review before a mutable project draft becomes the active immutable configuration of an environment.

## Environment policy

Each environment has two independent controls:

- `approvalRequired`: blocks the direct publication endpoint and requires an approved change request;
- `preventSelfApproval`: prevents the requester from approving their own change.

A missing policy preserves the development-friendly default: direct publication remains allowed and self-approval prevention defaults to enabled whenever a review is used.

## State machine

```text
DRAFT -> IN_REVIEW -> APPROVED -> PUBLISHED
                   \-> REJECTED
```

Only the requester can submit a draft request. `OWNER` and `ADMIN` memberships have `CHANGE_REQUEST_REVIEW`; `DEVELOPER` can create and submit requests but cannot approve or reject them. `VIEWER` can read policy and request history.

## Exact-candidate binding

Creation records:

- expected environment publication version;
- next candidate revision number;
- snapshot schema and allocation algorithm versions;
- deterministic SHA-256 checksum;
- the exact bounded binary snapshot payload that publication will persist.

Submission and approval rebuild the complete publication snapshot and compare its checksum in constant time with the stored candidate. The snapshot includes flags, variants, rules, prerequisites, segments, schema version, and allocation algorithm version. Any relevant draft change invalidates the request with `CANDIDATE_CHANGED`. A publication version change returns `VERSION_CONFLICT`.

Publication compares the checksum of the revision actually inserted with the approved checksum in the same transaction. A mismatch rolls back revision, snapshot, pointer, audit, and outbox changes. Approval therefore authorizes one exact snapshot, not a moving environment draft.

## HTTP API

```http
GET /api/v1/environments/{environmentId}/approval-policy
PUT /api/v1/environments/{environmentId}/approval-policy

POST /api/v1/environments/{environmentId}/change-requests
GET  /api/v1/environments/{environmentId}/change-requests
GET  /api/v1/environments/{environmentId}/change-requests/{changeRequestId}
GET  /api/v1/environments/{environmentId}/change-requests/{changeRequestId}/diff
POST /api/v1/environments/{environmentId}/change-requests/{changeRequestId}/submit
POST /api/v1/environments/{environmentId}/change-requests/{changeRequestId}/approve
POST /api/v1/environments/{environmentId}/change-requests/{changeRequestId}/reject
POST /api/v1/environments/{environmentId}/change-requests/{changeRequestId}/publish
```

Example policy:

```json
{
  "approvalRequired": true,
  "preventSelfApproval": true
}
```

Example request:

```json
{
  "expectedPublicationVersion": 3,
  "title": "Enable checkout-v2 for production",
  "description": "Reviewed release candidate"
}
```

## Review UI and diff

The Web Console loads the server-generated diff and displays stable paths such as `flags.checkout-v2.defaultVariant`, `targeting.flags.checkout-v2.rules.10.internal-beta.variant`, and `targeting.segments.staff.conditions.0`. Differences are classified as `ADDED`, `REMOVED`, or `CHANGED` with before/after values. A stale candidate disables approval in the UI; the backend remains the authorization and validity authority.

## Atomicity and audit

Every state mutation and its audit event run in the same PostgreSQL transaction. The audit trail records policy changes, creation, submission, approval, rejection, and publication without storing credentials or evaluation context.

Approved publication still uses the existing immutable revision transaction: revision, snapshot, current pointer, audit, and outbox remain atomic.

## Failure semantics

Workflow failures use RFC 9457 Problem Details with stable codes:

- `APPROVAL_REQUIRED`
- `SELF_APPROVAL_FORBIDDEN`
- `CANDIDATE_CHANGED`
- `VERSION_CONFLICT`
- `INVALID_TRANSITION`
- `ACTIVE_REQUEST_EXISTS`
- `INVALID_REQUEST`

The invariant is strict: approval of one candidate version cannot authorize a different configuration.

---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# Solicitações de mudança em ambientes protegidos

O FlagForge pode exigir uma revisão explícita antes que um rascunho mutável de projeto se torne a configuração imutável ativa de um ambiente.

## Política do ambiente

Cada ambiente tem dois controles independentes:

- `approvalRequired`: bloqueia o endpoint de publicação direta e exige uma solicitação de mudança aprovada;
- `preventSelfApproval`: impede que quem solicitou aprove a própria mudança.

A ausência de política preserva o padrão amigável ao desenvolvimento: a publicação direta continua permitida e a prevenção de autoaprovação vem habilitada por padrão sempre que uma revisão é usada.

## Máquina de estados

```text
DRAFT -> IN_REVIEW -> APPROVED -> PUBLISHED
                   \-> REJECTED
```

Somente quem solicitou pode submeter uma solicitação em rascunho. Associações `OWNER` e `ADMIN` possuem `CHANGE_REQUEST_REVIEW`; `DEVELOPER` pode criar e submeter solicitações, mas não pode aprová-las nem rejeitá-las. `VIEWER` pode ler a política e o histórico de solicitações.

## Vinculação ao candidato exato

A criação registra:

- a versão de publicação esperada do ambiente;
- o próximo número de revisão candidato;
- as versões do schema do snapshot e do algoritmo de alocação;
- o checksum SHA-256 determinístico;
- o payload binário limitado exato do snapshot que a publicação irá persistir.

A submissão e a aprovação reconstroem o snapshot completo de publicação e comparam seu checksum, em tempo constante, com o candidato armazenado. O snapshot inclui flags, variantes, regras, pré-requisitos, segmentos, versão do schema e versão do algoritmo de alocação. Qualquer mudança relevante de rascunho invalida a solicitação com `CANDIDATE_CHANGED`. Uma mudança de versão de publicação retorna `VERSION_CONFLICT`.

A publicação compara o checksum da revisão efetivamente inserida com o checksum aprovado, na mesma transação. Uma divergência reverte as mudanças de revisão, snapshot, ponteiro, auditoria e outbox. A aprovação, portanto, autoriza um snapshot exato, e não um rascunho de ambiente em movimento.

## API HTTP

```http
GET /api/v1/environments/{environmentId}/approval-policy
PUT /api/v1/environments/{environmentId}/approval-policy

POST /api/v1/environments/{environmentId}/change-requests
GET  /api/v1/environments/{environmentId}/change-requests
GET  /api/v1/environments/{environmentId}/change-requests/{changeRequestId}
GET  /api/v1/environments/{environmentId}/change-requests/{changeRequestId}/diff
POST /api/v1/environments/{environmentId}/change-requests/{changeRequestId}/submit
POST /api/v1/environments/{environmentId}/change-requests/{changeRequestId}/approve
POST /api/v1/environments/{environmentId}/change-requests/{changeRequestId}/reject
POST /api/v1/environments/{environmentId}/change-requests/{changeRequestId}/publish
```

Exemplo de política:

```json
{
  "approvalRequired": true,
  "preventSelfApproval": true
}
```

Exemplo de solicitação:

```json
{
  "expectedPublicationVersion": 3,
  "title": "Enable checkout-v2 for production",
  "description": "Reviewed release candidate"
}
```

## UI de revisão e diff

O Console Web carrega o diff gerado pelo servidor e exibe caminhos estáveis, como `flags.checkout-v2.defaultVariant`, `targeting.flags.checkout-v2.rules.10.internal-beta.variant` e `targeting.segments.staff.conditions.0`. As diferenças são classificadas como `ADDED`, `REMOVED` ou `CHANGED`, com valores de antes e depois. Um candidato defasado desabilita a aprovação na UI; o backend permanece a autoridade de autorização e de validade.

## Atomicidade e auditoria

Toda mutação de estado e seu evento de auditoria são executados na mesma transação PostgreSQL. A trilha de auditoria registra mudanças de política, criação, submissão, aprovação, rejeição e publicação, sem armazenar credenciais nem contexto de avaliação.

A publicação aprovada continua usando a transação existente de revisão imutável: revisão, snapshot, ponteiro atual, auditoria e outbox permanecem atômicos.

## Semântica de falha

As falhas de fluxo usam Problem Details (RFC 9457) com códigos estáveis:

- `APPROVAL_REQUIRED`
- `SELF_APPROVAL_FORBIDDEN`
- `CANDIDATE_CHANGED`
- `VERSION_CONFLICT`
- `INVALID_TRANSITION`
- `ACTIVE_REQUEST_EXISTS`
- `INVALID_REQUEST`

O invariante é estrito: a aprovação de uma versão candidata não pode autorizar uma configuração diferente.

</details>
