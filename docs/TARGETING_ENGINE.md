# Targeting engine semantics

## Scope

The targeting engine evaluates one immutable configuration using a typed context. It is deliberately pure: it does not query PostgreSQL, Redis, external services, clocks, or random sources during evaluation.

Equal normalized configuration and context inputs produce equal outputs.

## Evaluation order

For one requested flag, the engine performs these steps:

1. validate the complete configuration;
2. evaluate prerequisites in their declared order;
3. order targeting rules by ascending integer priority;
4. evaluate each rule's conditions with logical AND;
5. return the first matching rule;
6. return the declared default variant when no rule matches.

Two rules on the same flag cannot share a priority. Rule list order is therefore not a hidden tie-breaker.

A rule with no conditions is an explicit catch-all rule. It should normally have the lowest precedence through the largest priority number.

## Typed context

The initial context supports:

- string values;
- arbitrary-precision decimal numbers;
- boolean values.

Attribute names are stripped, converted to lowercase with locale-independent rules, and validated as stable keys. String values are not trimmed, lowercased, parsed, or otherwise coerced.

Numeric equality uses decimal numeric comparison, so `9.5` and `9.50` are equal. String `"18"` is not equal to numeric `18`.

The targeting key is required, remains whitespace-sensitive, and is normalized to Unicode NFC. Raw targeting keys and complete contexts must not be logged or used as metric labels.

## Conditions

### Typed equality

Equality requires compatible types:

- string compared with string;
- boolean compared with boolean;
- number compared with number.

A missing attribute does not match. An incompatible runtime type returns `ATTRIBUTE_TYPE_MISMATCH` rather than coercing the value.

### String set membership

A string attribute can be tested against a non-empty declared set. Membership is exact and case-sensitive.

### Numeric comparison

Supported operators are:

- `EQUAL`;
- `LESS_THAN`;
- `LESS_THAN_OR_EQUAL`;
- `GREATER_THAN`;
- `GREATER_THAN_OR_EQUAL`.

Both operands use `BigDecimal`; floating-point comparison is not used.

### Semantic version comparison

Semantic version conditions follow SemVer 2.0 precedence:

- major, minor, and patch are compared numerically;
- a release has higher precedence than its prerelease;
- numeric prerelease identifiers compare numerically;
- numeric prerelease identifiers have lower precedence than non-numeric ones;
- build metadata does not affect precedence.

Examples:

```text
1.0.0-alpha < 1.0.0-alpha.1 < 1.0.0-beta.11 < 1.0.0-rc.1 < 1.0.0
1.0.0+build.1 == 1.0.0+build.99
```

Malformed runtime versions return `INVALID_SEMANTIC_VERSION`. Malformed version operands are rejected during configuration validation.

## Segments

A segment may contain:

- explicit targeting-key inclusions;
- explicit targeting-key exclusions;
- typed conditions;
- positive or negated references to other segments.

Precedence is fixed:

1. explicit exclusion returns non-member;
2. explicit inclusion returns member;
3. all segment conditions must match;
4. a segment with no explicit membership and no conditions is non-member.

Therefore, exclusion wins when the same targeting key appears in both explicit sets.

Nested segments are supported, but cycles are rejected before evaluation. Runtime depth is also bounded defensively.

## Prerequisites

A prerequisite identifies another flag and the variant it must produce for the same evaluation context.

Prerequisites run before the target flag's rules. When a prerequisite returns another variant, the target flag returns:

```text
reason = PREREQUISITE_FAILED
variant = target flag default variant
failedPrerequisiteKey = prerequisite flag key
```

An error produced while evaluating a prerequisite is propagated as an error for the requested flag.

The complete prerequisite graph must be acyclic. Unknown flags, unknown expected variants, duplicate prerequisites, and cycles are rejected during configuration validation.

## Result model

Successful evaluations use one of these reasons:

- `TARGETING_MATCH` — a rule matched;
- `DEFAULT` — no rule matched;
- `PREREQUISITE_FAILED` — a prerequisite produced a different variant.

Errors use:

```text
reason = ERROR
variant = null
errorCode = stable machine-readable code
```

The engine does not expose exception classes, context contents, or targeting keys in the result.

## Missing and invalid input semantics

| Situation | Behavior |
| --- | --- |
| Missing attribute | condition does not match |
| Wrong attribute type | `ATTRIBUTE_TYPE_MISMATCH` |
| Invalid runtime SemVer | `INVALID_SEMANTIC_VERSION` |
| Unknown requested flag | `UNKNOWN_FLAG` |
| No rule matches | declared default variant |
| Unknown segment in configuration | validation failure |
| Unknown prerequisite | validation failure |
| Duplicate rule priority | validation failure |
| Cyclic segment graph | validation failure |
| Cyclic prerequisite graph | validation failure |

Configuration failures use `TargetingValidationException` with a stable `ErrorCode`. They are intended to block publication before an evaluator can observe malformed configuration.

## Bounds

The initial defensive limits are:

| Resource | Limit |
| --- | ---: |
| Flags per configuration | 256 |
| Segments per configuration | 256 |
| Rules per flag | 128 |
| Conditions per rule or segment | 32 |
| Context attributes | 256 |
| Segment/prerequisite graph depth | 64 |

These are correctness and termination guards, not final product-plan quotas.

## Publication integration

This issue defines the deterministic evaluation domain only. Snapshot publication will later compile persisted flags, variants, segments, rules, prerequisites, and rollout allocations into one immutable configuration.

Publication must validate:

- all references belong to the same organization, project, and environment;
- all rule targets reference declared variants;
- all graphs are acyclic;
- the snapshot records its targeting and rollout algorithm versions.

An evaluator must never assemble rules or segment definitions from multiple published versions.

## Test strategy

The suite proves:

- explicit priority and first-match behavior;
- typed equality without coercion;
- set, numeric, and SemVer operators;
- missing and invalid attribute semantics;
- segment inclusion/exclusion precedence;
- nested and negated segment references;
- prerequisite success and failure;
- duplicate-priority rejection;
- segment and prerequisite cycle rejection;
- key and Unicode normalization;
- deterministic repeated evaluation over a fixed 10,000-context sample.
