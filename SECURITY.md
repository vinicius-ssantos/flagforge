# Security Policy

## Project status

FlagForge is in early development and has no supported production release yet. Security reports are still welcome because tenant isolation and runtime configuration are core concerns.

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting for this repository when available. Do not create a public issue containing exploit details, credentials, tenant data, or reproduction secrets.

Include:

- Affected component and commit/version.
- Impact and required attacker capabilities.
- Reproduction steps or a minimal proof of concept.
- Suggested mitigation if known.

## Security boundaries

The security model treats the following as distinct boundaries:

- Organization/tenant.
- Human administrative access.
- Server-side SDK credential.
- Potential future client-side credential.
- Development, staging, and production environments.
- Control Plane and Evaluation Plane capabilities.

## Baseline requirements

- Tenant identity is derived from authenticated credentials.
- Secrets are stored as hashes or in an external secret store, never plaintext in the database.
- SDK keys are environment-scoped, revocable, rotatable, and least-privileged.
- Production changes are fully audited.
- Logs, traces, metrics, and errors do not expose credentials or sensitive targeting data.
- Cache keys include the tenant boundary.
- Cross-tenant negative tests accompany public resource APIs.
- Dependency and container scanning will be part of CI once executable code exists.

## Feature flags are not authorization

A flag may control product exposure but must not grant access to protected data or privileged operations. Applications integrating FlagForge remain responsible for authentication and authorization.

