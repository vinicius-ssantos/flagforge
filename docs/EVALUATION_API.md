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

An in-process cache serves a last-known-good document when PostgreSQL is unreachable, within a configured staleness budget (`flagforge.evaluation.cache.staleness-budget`). Past that budget the failure is surfaced as `SNAPSHOT_UNAVAILABLE` instead: an answer nobody can date is worse than an explicit error. A cache hit for the current version is never stale, because a cached document only ever answers for the version the pointer names.

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

---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# API de Avaliação

## Endpoint

```http
POST /api/v1/evaluate/{flagKey}
Authorization: Bearer <credencial de SDK do ambiente>
Content-Type: application/json
```

A requisição não aceita identificadores de organização, projeto ou ambiente. A credencial de SDK autenticada determina a organização e o ambiente, e o ambiente determina o projeto cujas flags podem ser avaliadas.

O contrato legível por máquina e os exemplos estão disponíveis em `docs/openapi/evaluation-api.yaml`.

## Contrato da requisição

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

Quem chama precisa declarar:

- o tipo esperado da flag;
- um valor de fallback exatamente desse tipo;
- uma chave de segmentação estável;
- atributos opcionais explicitamente tipados.

A API não converte `"false"` em `false`, `"18"` em `18`, nem strings arbitrárias em versões semânticas. Uma requisição tipada malformada é rejeitada com Problem Details (RFC 9457) e HTTP 400.

## Autenticação e isolamento de ambiente

A avaliação usa credenciais de SDK com escopo de ambiente e escopo `EVALUATE`.

A credencial Bearer é interpretada e verificada uma única vez pelo filtro de autenticação. O contexto de segurança armazena apenas o principal técnico do SDK:

- ID da credencial;
- ID da organização;
- ID do ambiente;
- escopo da credencial.

A credencial em texto plano não é retida após a autenticação. Credenciais ausentes, malformadas, revogadas, desconhecidas e inválidas produzem a mesma resposta genérica HTTP 401.

Como não existe seletor de ambiente na requisição, uma credencial válida não consegue pedir ao endpoint a avaliação de outro ambiente. A consulta ao banco também inclui a organização derivada da credencial.

## Contrato da resposta

Uma avaliação concluída retorna HTTP 200 e inclui:

- valor avaliado;
- tipo do valor;
- variante selecionada, quando existir;
- razão efetiva;
- razão de origem para resultados defasados;
- versão da configuração;
- metadados estáveis de erro;
- chave da regra que casou, quando relevante;
- chave do pré-requisito reprovado, quando relevante;
- bucket determinístico para divisões percentuais;
- indicador de defasagem.

Exemplo:

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

## Modelo de razões

| Razão | Significado |
| --- | --- |
| `TARGETING_MATCH` | Uma regra ordenada de segmentação selecionou a variante. |
| `SPLIT` | Uma alocação percentual determinística selecionou a variante. |
| `DEFAULT` | Nenhuma regra de segmentação casou e nenhuma divisão substituiu o padrão. |
| `DISABLED` | A flag não está ativa; o fallback de quem chamou é retornado. |
| `PREREQUISITE_FAILED` | Um pré-requisito produziu uma variante diferente; o padrão da flag é retornado. |
| `STALE` | Foi usado um snapshot de último estado bom conhecido. `sourceReason` preserva a decisão original. |
| `ERROR` | O fallback de quem chamou é retornado com metadados estáveis de erro. |

## Semântica de fallback

Flags desconhecidas, incompatibilidade com o tipo requisitado, configuração compilada inválida e indisponibilidade de snapshot retornam o fallback declarado por quem chamou, com `reason = ERROR`.

Trata-se de uma resposta de domínio, e não de um erro de transporte HTTP, porque quem chama via SDK precisa sempre receber um valor do tipo requisitado. Os metadados de erro explicam por que o fallback foi usado.

Requisições malformadas são diferentes: quando o servidor não consegue estabelecer um par válido de tipo requisitado e fallback, ele retorna HTTP 400.

## Fonte de configuração

A API depende de `EvaluationSnapshotProvider`, e não diretamente de uma tecnologia específica de persistência.

O provider inicial lê a flag atual do projeto e suas variantes do PostgreSQL e cria um identificador de versão transitório a partir das versões otimistas de ambiente e de flag. Ele deliberadamente ainda não contém regras de segmentação nem divisões percentuais persistidas, porque essas pertencem a revisões publicadas imutáveis.

O marco de publicação substituirá esse provider por snapshots imutáveis sem alterar os contratos HTTP ou de serviço. Um avaliador nunca deve combinar dados de regra, segmento, variante ou pré-requisito de versões publicadas diferentes.

## Divisões percentuais determinísticas

Um snapshot compilado pode conter alocações ordenadas de variantes cujas unidades inteiras somam exatamente 100.000. O serviço de avaliação usa `flagforge-rollout-v1` e retorna o bucket selecionado para fins de explicabilidade.

A mesma organização, projeto, ambiente, chave de flag, chave de alocação e chave de segmentação produzem o mesmo bucket. Os detalhes de alocação são dados de configuração e não são aceitos a partir da requisição de avaliação.

## Comportamento defasado (stale)

Um futuro provider de cache pode servir um snapshot validado de último estado bom conhecido durante a degradação de dependências. Esse resultado usa:

```text
reason = STALE
sourceReason = razão original da decisão
stale = true
configurationVersion = versão efetivamente servida
```

Um cache em processo serve o documento de último estado bom conhecido quando o PostgreSQL está inalcançável, dentro de um orçamento configurado de defasagem (`flagforge.evaluation.cache.staleness-budget`). Passado esse orçamento, a falha é exposta como `SNAPSHOT_UNAVAILABLE`: uma resposta que ninguém consegue datar é pior que um erro explícito. Um acerto de cache na versão corrente nunca é defasado, porque um documento em cache só responde pela versão que o ponteiro nomeia.

## Privacidade e telemetria

O endpoint de avaliação não registra em log corpos de requisição, chaves de segmentação em texto puro, credenciais de SDK ou atributos de contexto.

O contador inicial usa somente tags enumeradas e limitadas:

- razão;
- tipo de valor requisitado;
- código de erro;
- booleano de defasagem.

Chaves de flag, chaves de segmentação, IDs de organização, IDs de ambiente, chaves de regra e atributos arbitrários não são rótulos de métrica.

## Afirmações de desempenho

Esta issue estabelece apenas comportamento e instrumentação. Ela não declara meta de latência ou de throughput. Benchmarks reprodutíveis medirão posteriormente os caminhos de PostgreSQL frio, Redis quente e cache em processo quente antes de qualquer objetivo numérico ser proposto.

## Verificação

A suíte de testes cobre:

- validação exata de fallback tipado;
- resultados de segmentação, padrão, pré-requisito, divisão, desabilitado, defasado e erro;
- repetição determinística de divisão;
- fallback para flag desconhecida;
- incompatibilidade com o tipo requisitado;
- tags de métrica limitadas;
- falhas genéricas de credencial ausente e inválida;
- dois tenants com a mesma chave de flag resolvendo valores diferentes por meio de suas próprias chaves de SDK de ambiente;
- Problem Details estáveis para requisições malformadas.

</details>
