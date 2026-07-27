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
- Flyway migration from V1 through V6.
