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
- bounded canonical candidate payload.

Submission, approval, and publication recompile the current mutable draft and compare it in constant time with the stored checksum. Any flag or variant change invalidates the request with `CANDIDATE_CHANGED`. A publication version change returns `VERSION_CONFLICT`.

Approval therefore authorizes one exact candidate, not a moving environment draft.

## HTTP API

```http
GET /api/v1/environments/{environmentId}/approval-policy
PUT /api/v1/environments/{environmentId}/approval-policy

POST /api/v1/environments/{environmentId}/change-requests
GET  /api/v1/environments/{environmentId}/change-requests
GET  /api/v1/environments/{environmentId}/change-requests/{changeRequestId}
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
