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

---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# Semântica do motor de segmentação

## Escopo

O motor de segmentação avalia uma configuração imutável usando um contexto tipado. Ele é deliberadamente puro: não consulta PostgreSQL, Redis, serviços externos, relógios nem fontes aleatórias durante a avaliação.

Entradas normalizadas iguais de configuração e contexto produzem saídas iguais.

## Ordem de avaliação

Para uma flag requisitada, o motor executa estes passos:

1. validar a configuração completa;
2. avaliar os pré-requisitos na ordem declarada;
3. ordenar as regras de segmentação por prioridade inteira ascendente;
4. avaliar as condições de cada regra com AND lógico;
5. retornar a primeira regra que casar;
6. retornar a variante padrão declarada quando nenhuma regra casar.

Duas regras da mesma flag não podem compartilhar uma prioridade. A ordem da lista de regras, portanto, não é um critério de desempate oculto.

Uma regra sem condições é uma regra catch-all explícita. Normalmente ela deve ter a menor precedência, por meio do maior número de prioridade.

## Contexto tipado

O contexto inicial suporta:

- valores string;
- números decimais de precisão arbitrária;
- valores booleanos.

Nomes de atributo têm espaços removidos nas bordas, são convertidos para minúsculas com regras independentes de locale e validados como chaves estáveis. Valores string não são aparados, convertidos para minúsculas, interpretados nem coagidos de qualquer outra forma.

A igualdade numérica usa comparação numérica decimal, de modo que `9.5` e `9.50` são iguais. A string `"18"` não é igual ao número `18`.

A chave de segmentação é obrigatória, permanece sensível a espaços em branco e é normalizada para Unicode NFC. Chaves de segmentação em texto puro e contextos completos não devem ser registrados em log nem usados como rótulos de métrica.

## Condições

### Igualdade tipada

A igualdade exige tipos compatíveis:

- string comparada com string;
- booleano comparado com booleano;
- número comparado com número.

Um atributo ausente não casa. Um tipo incompatível em tempo de execução retorna `ATTRIBUTE_TYPE_MISMATCH`, em vez de coagir o valor.

### Pertencimento a conjunto de strings

Um atributo string pode ser testado contra um conjunto declarado não vazio. O pertencimento é exato e sensível a maiúsculas e minúsculas.

### Comparação numérica

Os operadores suportados são:

- `EQUAL`;
- `LESS_THAN`;
- `LESS_THAN_OR_EQUAL`;
- `GREATER_THAN`;
- `GREATER_THAN_OR_EQUAL`.

Ambos os operandos usam `BigDecimal`; comparação em ponto flutuante não é utilizada.

### Comparação de versão semântica

Condições de versão semântica seguem a precedência do SemVer 2.0:

- major, minor e patch são comparados numericamente;
- um release tem precedência maior que seu prerelease;
- identificadores numéricos de prerelease são comparados numericamente;
- identificadores numéricos de prerelease têm precedência menor que os não numéricos;
- metadados de build não afetam a precedência.

Exemplos:

```text
1.0.0-alpha < 1.0.0-alpha.1 < 1.0.0-beta.11 < 1.0.0-rc.1 < 1.0.0
1.0.0+build.1 == 1.0.0+build.99
```

Versões malformadas em tempo de execução retornam `INVALID_SEMANTIC_VERSION`. Operandos de versão malformados são rejeitados durante a validação da configuração.

## Segmentos

Um segmento pode conter:

- inclusões explícitas por chave de segmentação;
- exclusões explícitas por chave de segmentação;
- condições tipadas;
- referências positivas ou negadas a outros segmentos.

A precedência é fixa:

1. exclusão explícita retorna não membro;
2. inclusão explícita retorna membro;
3. todas as condições do segmento precisam casar;
4. um segmento sem pertencimento explícito e sem condições é não membro.

Portanto, a exclusão vence quando a mesma chave de segmentação aparece em ambos os conjuntos explícitos.

Segmentos aninhados são suportados, mas ciclos são rejeitados antes da avaliação. A profundidade em tempo de execução também é limitada de forma defensiva.

## Pré-requisitos

Um pré-requisito identifica outra flag e a variante que ela precisa produzir para o mesmo contexto de avaliação.

Pré-requisitos são executados antes das regras da flag alvo. Quando um pré-requisito retorna outra variante, a flag alvo retorna:

```text
reason = PREREQUISITE_FAILED
variant = variante padrão da flag alvo
failedPrerequisiteKey = chave da flag de pré-requisito
```

Um erro produzido ao avaliar um pré-requisito é propagado como erro para a flag requisitada.

O grafo completo de pré-requisitos precisa ser acíclico. Flags desconhecidas, variantes esperadas desconhecidas, pré-requisitos duplicados e ciclos são rejeitados durante a validação da configuração.

## Modelo de resultado

Avaliações bem-sucedidas usam uma destas razões:

- `TARGETING_MATCH` — uma regra casou;
- `DEFAULT` — nenhuma regra casou;
- `PREREQUISITE_FAILED` — um pré-requisito produziu uma variante diferente.

Erros usam:

```text
reason = ERROR
variant = null
errorCode = código estável legível por máquina
```

O motor não expõe classes de exceção, conteúdo do contexto nem chaves de segmentação no resultado.

## Semântica de entradas ausentes e inválidas

| Situação | Comportamento |
| --- | --- |
| Atributo ausente | a condição não casa |
| Tipo de atributo incorreto | `ATTRIBUTE_TYPE_MISMATCH` |
| SemVer inválido em tempo de execução | `INVALID_SEMANTIC_VERSION` |
| Flag requisitada desconhecida | `UNKNOWN_FLAG` |
| Nenhuma regra casa | variante padrão declarada |
| Segmento desconhecido na configuração | falha de validação |
| Pré-requisito desconhecido | falha de validação |
| Prioridade de regra duplicada | falha de validação |
| Grafo de segmentos cíclico | falha de validação |
| Grafo de pré-requisitos cíclico | falha de validação |

Falhas de configuração usam `TargetingValidationException` com um `ErrorCode` estável. Elas existem para bloquear a publicação antes que um avaliador possa observar configuração malformada.

## Limites

Os limites defensivos iniciais são:

| Recurso | Limite |
| --- | ---: |
| Flags por configuração | 256 |
| Segmentos por configuração | 256 |
| Regras por flag | 128 |
| Condições por regra ou segmento | 32 |
| Atributos de contexto | 256 |
| Profundidade do grafo de segmentos/pré-requisitos | 64 |

Esses são guardas de correção e terminação, não cotas finais de plano de produto.

## Integração com a publicação

Esta issue define apenas o domínio de avaliação determinística. A publicação de snapshots compilará, mais adiante, flags, variantes, segmentos, regras, pré-requisitos e alocações de rollout persistidos em uma única configuração imutável.

A publicação precisa validar:

- que todas as referências pertencem à mesma organização, projeto e ambiente;
- que todos os alvos de regra referenciam variantes declaradas;
- que todos os grafos são acíclicos;
- que o snapshot registra suas versões de algoritmo de segmentação e de rollout.

Um avaliador nunca deve montar regras ou definições de segmento a partir de múltiplas versões publicadas.

## Estratégia de testes

A suíte comprova:

- comportamento explícito de prioridade e de primeira correspondência;
- igualdade tipada sem coerção;
- operadores de conjunto, numéricos e SemVer;
- semântica de atributos ausentes e inválidos;
- precedência de inclusão/exclusão em segmentos;
- referências a segmentos aninhados e negados;
- sucesso e falha de pré-requisito;
- rejeição de prioridade duplicada;
- rejeição de ciclos de segmento e de pré-requisito;
- normalização de chaves e Unicode;
- avaliação repetida determinística sobre uma amostra fixa de 10.000 contextos.

</details>
