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

`PublicationService.publish(environmentId)` performs the following work inside
one PostgreSQL transaction:

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

Snapshot schema version 1 uses a deterministic binary encoding rather than
runtime-dependent object serialization. The wire format includes:

- the `FFSNAP01` magic header;
- schema version;
- tenant, project, and environment UUIDs;
- revision number;
- algorithm version;
- sorted feature flags;
- sorted typed variants and their values.

The current schema supports BOOLEAN and STRING flag values. Keys and strings
use explicit UTF-8 byte limits. Counts are bounded, trailing bytes are rejected,
and the complete payload cannot exceed 1 MiB.

Compatibility tests pin a fixed byte length and checksum for a known snapshot.
Any accidental wire-format change therefore fails verification instead of
silently producing incompatible runtime data.

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

Publication inserts one `CONFIGURATION_PUBLISHED` outbox record in the same
transaction as the revision and pointer. The relay, retries, deduplication, and
external cache/distribution behavior are delivered separately by issue #18.
Until then, events remain safely persisted with status `PENDING`.

## Concurrency scope

The environment row is locked while allocating and committing a revision, so
concurrent publications cannot receive the same revision number or interleave
pointer updates. Client-supplied expected-version conflict semantics and richer
draft workflow concurrency belong to issue #15.

## Verification coverage

Automated tests verify:

- revision, snapshot, pointer, audit, and outbox persistence;
- draft edits remain invisible before republishing;
- failed publication leaves the prior revision effective;
- revision history rejects mutation and deletion;
- viewers cannot publish;
- checksum tampering and unsupported schema rejection;
- deterministic compatibility vectors and payload bounds;
- evaluation API fixtures publish before runtime evaluation.
