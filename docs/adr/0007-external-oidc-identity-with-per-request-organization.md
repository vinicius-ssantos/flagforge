# ADR 0007: Authenticate operators with external OIDC and select the organization per request

- Status: Accepted
- Date: 2026-09-02

## Context

Every Control Plane route required an authenticated principal, but nothing could produce one. No
identity provider was integrated, no login path existed, and the local `UserDetailsService`
rejected every lookup. RBAC, tenant isolation, and the SDK credential lifecycle were all
implemented and unreachable, so the platform could only be driven from tests.

Two questions had to be answered together. Where does an operator's identity come from, and how
does a request say which organization it acts in? The second is not incidental: `memberships` is
keyed by `(organization_id, actor_id)`, so one person can belong to several organizations, and the
existing `TenantPrincipal` carries an organization identifier that a bare token cannot supply.

## Decision

Operator identity comes from an external OIDC issuer. FlagForge acts as an OAuth2 resource server,
validates the JWT, and takes the actor from its `sub` claim. No password, password hash, or human
credential is stored in the database.

The organization arrives per request in the `X-FlagForge-Organization` header, carrying the
organization slug. The header alone grants nothing: the authentication filter resolves it to an
organization and requires an ACTIVE membership for that actor before building a `TenantPrincipal`.
Authorization below that point is unchanged — each application service still calls
`TenantAuthorizationService.require(permission)`.

A request that authenticates but cannot resolve a tenant receives an `ActorPrincipal`, which
reaches only organization registration. That operation is the one bootstrap with no pre-existing
tenant, and it takes its founding actor from the token, never from the request body.

Local development uses a `dev`-profile issuer that signs tokens with an RSA key generated in memory
at startup. Production validates against a configured issuer; when none is configured, no decoder
exists and the control plane stays closed.

## Consequences

### Positive

- No human credential store, so no password hashing, reset flow, or credential breach surface.
- Multi-organization operators switch tenants with a header instead of a new token.
- Existing routes and every application service signature are unchanged.
- A developer can exercise the control plane without running an identity provider.
- An unconfigured deployment is closed by default rather than open by default.

### Negative

- A deployment cannot authenticate operators until an OIDC issuer is configured.
- The organization selector is a header rather than part of the URL, so it is invisible in access
  logs and route templates unless deliberately recorded.
- The `dev` issuer signs a token for any actor asked for, which is safe only because it is confined
  to one profile; that confinement is now a security boundary a test must keep proving.
- Token revocation follows the issuer's semantics, so a revoked operator retains access until the
  token expires.

## Alternatives considered

**Organization in the URL path.** More explicit and visible in logs, but the delivered routes
(`/api/v1/environments/{id}/...`) carry no organization segment, so the repository would end up with
two addressing styles or would have to break published contracts.

**Organization claim inside the token.** Nothing would travel outside the authenticated context,
but it couples the identity provider to FlagForge's domain — the issuer would need to know about
organizations — and switching organizations would require a new token.

**Local user store with passwords.** Rejected: it contradicts `docs/ARCHITECTURE.md`, which already
states that human access uses OIDC/OAuth2, and it would add a credential breach surface the product
gains nothing from owning.

## Revisit when

- An operator population needs immediate revocation rather than token expiry.
- Organization-scoped rate limiting or per-organization access logs make the header's invisibility
  a practical problem.
- Enterprise federation or SCIM provisioning enters scope, which may move membership itself under
  the identity provider's control.

---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# ADR 0007: Autenticar operadores com OIDC externo e selecionar a organização por requisição

- Status: Aceito
- Data: 2026-09-02

## Contexto

Toda rota do Plano de Controle exigia um principal autenticado, mas nada conseguia produzir um.
Nenhum provedor de identidade estava integrado, não havia caminho de login e o `UserDetailsService`
local rejeitava toda consulta. RBAC, isolamento de tenant e o ciclo de vida das credenciais de SDK
estavam implementados e inalcançáveis, de modo que a plataforma só podia ser dirigida pelos testes.

Duas perguntas precisavam ser respondidas juntas. De onde vem a identidade de um operador, e como
uma requisição diz em qual organização ela age? A segunda não é acessória: `memberships` é chaveada
por `(organization_id, actor_id)`, então uma pessoa pode pertencer a várias organizações, e o
`TenantPrincipal` existente carrega um identificador de organização que um token sozinho não
fornece.

## Decisão

A identidade do operador vem de um emissor OIDC externo. O FlagForge atua como resource server
OAuth2, valida o JWT e toma o ator da claim `sub`. Nenhuma senha, hash de senha ou credencial
humana é armazenada no banco de dados.

A organização chega a cada requisição no header `X-FlagForge-Organization`, com o slug da
organização. O header sozinho não concede nada: o filtro de autenticação o resolve para uma
organização e exige uma associação ACTIVE daquele ator antes de montar um `TenantPrincipal`. A
autorização abaixo desse ponto não muda — cada serviço de aplicação continua chamando
`TenantAuthorizationService.require(permission)`.

Uma requisição que autentica mas não resolve um tenant recebe um `ActorPrincipal`, que alcança
apenas o registro de organização. Essa é a única operação de bootstrap sem tenant prévio, e ela
toma o ator fundador do token, nunca do corpo da requisição.

O desenvolvimento local usa um emissor do perfil `dev` que assina tokens com uma chave RSA gerada em
memória no startup. Produção valida contra um emissor configurado; quando nenhum está configurado,
não existe decoder e o plano de controle permanece fechado.

## Consequências

### Positivas

- Sem store de credenciais humanas, portanto sem hashing de senha, fluxo de redefinição ou
  superfície de vazamento de credenciais.
- Operadores em várias organizações trocam de tenant com um header, em vez de um novo token.
- As rotas existentes e todas as assinaturas de serviço de aplicação permanecem inalteradas.
- Uma pessoa desenvolvedora exercita o plano de controle sem subir um provedor de identidade.
- Um deploy não configurado fica fechado por padrão, em vez de aberto por padrão.

### Negativas

- Um deploy não consegue autenticar operadores até que um emissor OIDC seja configurado.
- O seletor de organização é um header, e não parte da URL, então fica invisível em logs de acesso e
  templates de rota, salvo se registrado deliberadamente.
- O emissor de `dev` assina token para qualquer ator solicitado, o que só é seguro porque está
  confinado a um perfil; esse confinamento passa a ser uma fronteira de segurança que um teste
  precisa continuar comprovando.
- A revogação de token segue a semântica do emissor, então um operador revogado mantém acesso até o
  token expirar.

## Alternativas consideradas

**Organização no path da URL.** Mais explícito e visível em logs, mas as rotas já entregues
(`/api/v1/environments/{id}/...`) não têm segmento de organização, então o repositório acabaria com
dois estilos de endereçamento ou teria de quebrar contratos publicados.

**Claim de organização dentro do token.** Nada trafegaria fora do contexto autenticado, mas isso
acopla o provedor de identidade ao domínio do FlagForge — o emissor precisaria conhecer organizações
— e trocar de organização exigiria um novo token.

**Store local de usuários com senha.** Rejeitada: contradiz a `docs/ARCHITECTURE.md`, que já
determina que o acesso humano usa OIDC/OAuth2, e acrescentaria uma superfície de vazamento de
credenciais que o produto não ganha nada em possuir.

## Revisitar quando

- Uma população de operadores precisar de revogação imediata, em vez de expiração de token.
- Limitação de taxa por organização ou logs de acesso por organização tornarem a invisibilidade do
  header um problema prático.
- Federação enterprise ou provisionamento SCIM entrarem em escopo, o que pode levar a própria
  associação para o controle do provedor de identidade.

</details>
