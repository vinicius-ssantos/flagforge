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


---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# Política de Segurança

## Status do projeto

O FlagForge está em desenvolvimento inicial e ainda não possui um release de produção suportado. Relatos de segurança continuam sendo bem-vindos porque isolamento entre tenants e configuração em tempo de execução são preocupações centrais.

## Reportando uma vulnerabilidade

Use o relato privado de vulnerabilidades do GitHub para este repositório quando disponível. Não crie uma issue pública contendo detalhes de exploração, credenciais, dados de tenants ou segredos de reprodução.

Inclua:

- Componente afetado e commit/versão.
- Impacto e capacidades necessárias ao atacante.
- Passos de reprodução ou uma prova de conceito mínima.
- Mitigação sugerida, se conhecida.

## Fronteiras de segurança

O modelo de segurança trata os itens a seguir como fronteiras distintas:

- Organização/tenant.
- Acesso administrativo humano.
- Credencial de SDK do lado do servidor.
- Possível credencial futura do lado do cliente.
- Ambientes de desenvolvimento, staging e produção.
- Capacidades do Plano de Controle e do Plano de Avaliação.

## Requisitos de baseline

- A identidade do tenant é derivada de credenciais autenticadas.
- Segredos são armazenados como hashes ou em um cofre externo, nunca em texto plano no banco de dados.
- Chaves de SDK têm escopo de ambiente, são revogáveis, rotacionáveis e de menor privilégio.
- Mudanças em produção são integralmente auditadas.
- Logs, traces, métricas e erros não expõem credenciais nem dados sensíveis de segmentação.
- Chaves de cache incluem a fronteira do tenant.
- Testes negativos entre tenants acompanham as APIs públicas de recursos.
- Varredura de dependências e de contêineres fará parte da CI assim que houver código executável.

## Feature flags não são autorização

Uma flag pode controlar a exposição do produto, mas não deve conceder acesso a dados protegidos ou operações privilegiadas. As aplicações que integram o FlagForge permanecem responsáveis por autenticação e autorização.

</details>
