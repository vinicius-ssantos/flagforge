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
