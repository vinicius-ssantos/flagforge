# Initial Threat Model

This document records the minimum security and observability boundaries that must exist before FlagForge begins implementing tenant-owned resources. It is intentionally scoped to the M0 Control API foundation and must evolve with the domain.

## Protected assets

- Tenant-owned configuration and resource existence.
- Environment-scoped SDK credentials and future operator sessions.
- Published feature-flag snapshots and audit history.
- PostgreSQL credentials and application configuration secrets.
- Targeting keys, user attributes, and complete evaluation contexts.
- Operational telemetry that could reveal customer or credential data.

## Trust boundaries

```mermaid
flowchart LR
    CLIENT["Untrusted HTTP client"] --> INGRESS["Servlet and Spring Security filters"]
    INGRESS --> API["FlagForge Control API"]
    API --> PG[("PostgreSQL")]
    API -. "opt-in traces" .-> OTLP["Configured OTLP collector"]
    ENV["Deployment configuration"] --> API
```

1. Every inbound HTTP request is untrusted, including headers such as `X-Correlation-ID`.
2. The security filter chain is the boundary before application routes and tenant lookups.
3. PostgreSQL is trusted as the application source of truth, but values read from it are still treated as data rather than executable input.
4. Environment variables and secret injection are deployment responsibilities. Local defaults are not production credentials.
5. OTLP export is outbound and disabled by default. Enabling it establishes a new trust relationship with the configured collector.

## Baseline controls

### Authentication and resource enumeration

Only minimal health and build-information endpoints are public during M0. Every application route is default-denied until the authentication and RBAC model is implemented in issue #8.

Authentication failures use one generic RFC 9457 Problem Details contract. The response does not indicate whether an organization, project, environment, flag, or other tenant-owned resource exists. Future handlers must resolve tenant identity from the authenticated principal before performing resource lookup.

### Request correlation

The API accepts `X-Correlation-ID` only when it contains 1 to 64 characters from a bounded safe character set. Invalid or missing values are replaced with a UUID. The effective identifier is returned to the caller and added to the logging MDC.

A correlation identifier is diagnostic metadata, not an authentication token, idempotency key, or authorization input.

### Logging and redaction

Console logs use structured ECS output. Trace identifiers and the effective correlation identifier can be included through MDC fields.

The application must not log:

- authorization or cookie headers;
- passwords, tokens, SDK keys, or database credentials;
- complete targeting contexts or arbitrary user attributes;
- raw request or response bodies by default.

When a diagnostic event needs user-related context, it must use an approved bounded category or an irreversible, explicitly reviewed representation. Logging utilities introduced later must centralize redaction rather than relying on callers to remember it.

### Metrics and traces

Metric labels must be bounded. A global meter filter rejects known sensitive or high-cardinality identity keys such as `targeting.key`, `subject.id`, `user.id`, `organization.id`, `sdk.key`, `credential`, and `token`.

Acceptable dimensions include bounded values such as evaluation reason, result type, cache layer, endpoint template, and success or failure category. Raw identifiers belong in neither metric names nor labels.

OpenTelemetry integration is present, but OTLP export is disabled by default. Sampling is configurable, and complete targeting contexts must never be added as span attributes.

### Health behavior

- Liveness reports only whether the process should be restarted and does not depend on PostgreSQL.
- Readiness includes PostgreSQL because the Control API cannot safely serve its principal workflows without its source of truth.
- Health details are never returned publicly.
- `/livez` and `/readyz` mirror the actuator probe groups on the main server port.

### HTTP responses

Responses include defensive browser headers even though the current service is an API. Error responses do not include exception types, stack traces, binding internals, secrets, or database details.

## Principal threats and mitigations

| Threat | Initial mitigation | Follow-up |
|---|---|---|
| Tenant resource enumeration | Authentication before lookup and generic failures | Negative isolation tests in #7 and #8 |
| Credential or context leakage in telemetry | No body/header logging, structured redaction policy, prohibited metric tag keys | Credential-specific tests in #8 and SDK tests in #20 |
| High-cardinality metric exhaustion | Global deny filter and bounded-tag guidance | Benchmark and telemetry review in #21 |
| Malicious correlation header | Strict validation, length limit, generated fallback | Preserve same policy across gateways and SDKs |
| Accidental public endpoint | Explicit actuator allowlist and `denyAll` fallback | Authorization matrix in #8 |
| Misleading health status | Separate liveness and database-backed readiness | Dependency-specific readiness review as services evolve |
| Trace data sent unintentionally | OTLP export disabled by default | Deployment checklist before enabling exporters |
| Detailed internal error leakage | Stable Problem Details and disabled stack/message exposure | Domain-specific problem catalog in later slices |

## Residual risk

M0 does not yet provide real operator authentication, tenant membership, SDK credentials, authorization roles, rate limiting, or protected-environment approval. Those capabilities remain explicit follow-up work. The current default-deny posture prevents placeholder or future routes from becoming anonymously accessible while those boundaries are still under development.

Any change that introduces a new external dependency, credential type, public endpoint, tenant lookup, telemetry exporter, or request-body logging must update this threat model in the same pull request.
