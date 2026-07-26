# Evaluation API

## Endpoint

```http
POST /api/v1/evaluate/{flagKey}
Authorization: Bearer <environment SDK credential>
Content-Type: application/json
```

The request does not accept organization, project, or environment identifiers. The authenticated SDK credential determines the organization and environment, and the environment determines the project whose flags can be evaluated.

The machine-readable contract and examples are available in `docs/openapi/evaluation-api.yaml`.

## Request contract

```json
{
  "type": "BOOLEAN",
  "defaultValue": false,
  "targetingKey": "subject-123",
  "attributes": {
    "country": {
      "type": "STRING",
      "value": "BR"
    },
    "score": {
      "type": "NUMBER",
      "value": 91.5
    }
  }
}
```

The caller must declare:

- the expected flag type;
- a fallback value of exactly that type;
- a stable targeting key;
- optional explicitly typed attributes.

The API does not convert `"false"` to `false`, `"18"` to `18`, or arbitrary strings to semantic versions. A malformed typed request is rejected with RFC 9457 Problem Details and HTTP 400.

## Authentication and environment isolation

Evaluation uses environment-scoped SDK credentials with the `EVALUATE` scope.

The Bearer credential is parsed and verified once by the authentication filter. The security context stores only the technical SDK principal:

- credential ID;
- organization ID;
- environment ID;
- credential scope.

The plaintext credential is not retained after authentication. Missing, malformed, revoked, unknown, and invalid credentials produce the same generic HTTP 401 response.

Because no environment selector exists in the request, a valid credential cannot ask the endpoint to evaluate another environment. The database lookup also includes the organization derived from the credential.

## Response contract

A completed evaluation returns HTTP 200 and includes:

- evaluated value;
- value type;
- selected variant when one exists;
- effective reason;
- source reason for stale results;
- configuration version;
- stable error metadata;
- matched rule key when relevant;
- failed prerequisite key when relevant;
- deterministic bucket for percentage splits;
- stale indicator.

Example:

```json
{
  "flagKey": "checkout-v2",
  "valueType": "BOOLEAN",
  "value": true,
  "variant": "enabled",
  "reason": "TARGETING_MATCH",
  "sourceReason": null,
  "configurationVersion": "revision-42",
  "error": {
    "code": "NONE",
    "message": null
  },
  "matchedRuleKey": "internal-beta",
  "failedPrerequisiteKey": null,
  "bucket": null,
  "stale": false
}
```

## Reason model

| Reason | Meaning |
| --- | --- |
| `TARGETING_MATCH` | An ordered targeting rule selected the variant. |
| `SPLIT` | A deterministic percentage allocation selected the variant. |
| `DEFAULT` | No targeting rule matched and no split replaced the default. |
| `DISABLED` | The flag is not active; the caller fallback is returned. |
| `PREREQUISITE_FAILED` | A prerequisite produced a different variant; the flag default is returned. |
| `STALE` | A last-known-good snapshot was used. `sourceReason` preserves the original decision. |
| `ERROR` | The caller fallback is returned with stable error metadata. |

## Fallback semantics

Unknown flags, requested-type mismatches, invalid compiled configuration, and snapshot unavailability return the caller-declared fallback with `reason = ERROR`.

This is a domain response rather than an HTTP transport error because SDK callers must always receive a value of the requested type. The error metadata explains why the fallback was used.

Malformed requests are different: when the server cannot establish a valid requested type and fallback pair, it returns HTTP 400.

## Configuration source

The API depends on `EvaluationSnapshotProvider`, not directly on a specific persistence technology.

The initial provider reads the current project flag and variants from PostgreSQL and creates a transitional version identifier from environment and flag optimistic versions. It deliberately contains no persisted targeting rules or percentage splits yet because those belong to immutable published revisions.

The publication milestone will replace this provider with immutable snapshots without changing the HTTP or service contracts. An evaluator must never combine rule, segment, variant, or prerequisite data from different published versions.

## Deterministic percentage splits

A compiled snapshot may contain ordered variant allocations whose integer units total exactly 100,000. The evaluation service uses `flagforge-rollout-v1` and returns the selected bucket for explainability.

The same organization, project, environment, flag key, allocation key, and targeting key produce the same bucket. Allocation details are configuration data and are not accepted from the evaluation request.

## Stale behavior

A future cache provider may serve a validated last-known-good snapshot during dependency degradation. Such a result uses:

```text
reason = STALE
sourceReason = original decision reason
stale = true
configurationVersion = version actually served
```

The current PostgreSQL provider reports `stale = false`. This contract is defined now so cache and failure-recovery work can be added without an API-breaking change.

## Privacy and telemetry

The evaluation endpoint does not log request bodies, raw targeting keys, SDK credentials, or context attributes.

The initial counter uses only bounded enumerated tags:

- reason;
- requested value type;
- error code;
- stale boolean.

Flag keys, targeting keys, organization IDs, environment IDs, rule keys, and arbitrary attributes are not metric labels.

## Performance claims

This issue establishes behavior and instrumentation only. It does not declare a latency or throughput target. Reproducible benchmarks will later measure cold PostgreSQL, warm Redis, and warm in-process cache paths before numeric objectives are proposed.

## Verification

The test suite covers:

- exact typed fallback validation;
- targeting, default, prerequisite, split, disabled, stale, and error results;
- deterministic split repetition;
- unknown-flag fallback;
- requested-type mismatch;
- bounded metric tags;
- generic missing and invalid credential failures;
- two tenants with the same flag key resolving different values through their own environment SDK keys;
- stable Problem Details for malformed requests.
