# Contributing to FlagForge

FlagForge is currently an architecture-led portfolio project. Contributions should preserve its emphasis on explicit domain invariants, tenant isolation, reproducible evidence, and earned complexity.

## Before opening a change

- Search existing issues and ADRs.
- Use an issue for behavior changes or architectural work.
- Propose an ADR when a change affects module boundaries, consistency, security, persistence ownership, public contracts, or failure semantics.
- Keep each pull request focused on one coherent outcome.

## Development workflow

The executable project commands will be documented with the M0 foundation implementation. Until then, do not add speculative setup instructions.

Once the build exists, every pull request is expected to run:

- Formatting and static analysis.
- Unit and module tests.
- Architecture verification.
- Integration tests relevant to the change.

## Pull request expectations

Describe:

- The problem being solved.
- User-visible and operational behavior.
- Invariants added or affected.
- Failure and fallback behavior.
- Tests and evidence.
- Security, tenant isolation, and observability impact.
- Documentation or ADR changes.

## Commit style

Use clear, imperative commits. Conventional prefixes are encouraged:

```text
feat: add deterministic rollout allocation
fix: prevent stale version pointer regression
test: cover cross-tenant segment lookup
docs: record snapshot versioning decision
chore: configure Java toolchain
```

## Design rules

- PostgreSQL remains authoritative.
- No direct access to another module's internal package or owned tables.
- Do not add distributed infrastructure without a use case and failure test.
- Do not use feature flags as authorization.
- Do not log credentials or complete targeting contexts.
- Every tenant-owned lookup must prove its tenant boundary.

## Reporting security issues

Do not open public issues for vulnerabilities. Follow [SECURITY.md](SECURITY.md).


---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# Como contribuir com o FlagForge

O FlagForge é atualmente um projeto de portfólio conduzido pela arquitetura. As contribuições devem preservar sua ênfase em invariantes de domínio explícitos, isolamento entre tenants, evidência reprodutível e complexidade conquistada.

## Antes de abrir uma mudança

- Pesquise issues e ADRs existentes.
- Use uma issue para mudanças de comportamento ou trabalho arquitetural.
- Proponha um ADR quando a mudança afetar fronteiras de módulo, consistência, segurança, propriedade da persistência, contratos públicos ou semântica de falha.
- Mantenha cada pull request focado em um único resultado coerente.

## Fluxo de desenvolvimento

Os comandos executáveis do projeto serão documentados junto com a implementação da fundação M0. Até lá, não adicione instruções especulativas de configuração.

Assim que o build existir, espera-se que todo pull request execute:

- Formatação e análise estática.
- Testes unitários e de módulo.
- Verificação de arquitetura.
- Testes de integração relevantes à mudança.

## Expectativas para o pull request

Descreva:

- O problema que está sendo resolvido.
- O comportamento visível ao usuário e o comportamento operacional.
- Invariantes adicionados ou afetados.
- Comportamento de falha e de fallback.
- Testes e evidências.
- Impacto em segurança, isolamento entre tenants e observabilidade.
- Mudanças de documentação ou ADR.

## Estilo de commit

Use commits claros e no imperativo. Prefixos convencionais são incentivados:

```text
feat: add deterministic rollout allocation
fix: prevent stale version pointer regression
test: cover cross-tenant segment lookup
docs: record snapshot versioning decision
chore: configure Java toolchain
```

## Regras de design

- O PostgreSQL permanece autoritativo.
- Nenhum acesso direto ao pacote interno ou às tabelas de outro módulo.
- Não adicione infraestrutura distribuída sem um caso de uso e um teste de falha.
- Não use feature flags como autorização.
- Não registre em log credenciais ou contextos de segmentação completos.
- Toda consulta a recurso pertencente a um tenant deve comprovar sua fronteira de tenant.

## Relatando problemas de segurança

Não abra issues públicas para vulnerabilidades. Siga o [SECURITY.md](SECURITY.md).

</details>
