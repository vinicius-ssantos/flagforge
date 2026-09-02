# Deterministic rollout allocation

## Purpose

FlagForge assigns a stable integer bucket to one targeting subject inside one flag allocation namespace. The bucket is reusable across evaluation processes, published revisions, and language SDKs as long as all implementations use the same algorithm version and inputs.

The algorithm supports explainable percentage rollouts. It is not a password hash, credential verifier, anonymization mechanism, or authorization boundary.

## Version identity

| Field | Value |
| --- | --- |
| Algorithm ID | `flagforge-rollout-v1` |
| Wire version | unsigned byte `0x01` |
| Hash | SHA-256 |
| Text encoding | UTF-8 |
| Unicode normalization | NFC for the targeting key |
| Bucket range | `0..99,999` |
| Percentage resolution | `0.001%` |

The algorithm ID must be persisted in every immutable snapshot that contains a percentage allocation. It must also be returned in detailed evaluation and diagnostic contracts when a bucket is relevant.

## Canonical hash payload

The payload begins with these bytes:

```text
46 46 52 41 01
 F  F  R  A  v1
```

Six fields follow in this exact order:

1. organization ID;
2. project ID;
3. environment ID;
4. flag key;
5. allocation key;
6. targeting key.

Each field is encoded as:

```text
4-byte unsigned big-endian UTF-8 byte length
UTF-8 field bytes
```

Length prefixes prevent ambiguous delimiter combinations such as `ab|c` and `a|bc` from producing the same payload.

### Field normalization

- UUIDs use the canonical lowercase hyphenated representation produced by `UUID.toString()`.
- Flag and allocation keys are stripped, converted to lowercase with locale-independent rules, and must match `[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?`.
- The targeting key is normalized to Unicode NFC.
- Targeting-key leading and trailing whitespace remain significant and are not stripped.
- A targeting key must not be blank and is limited to 1,024 UTF-8 bytes.

The allocation key is a stable namespace selected by the compiled configuration. Keeping it unchanged preserves the cohort across percentage changes and published revisions. Changing it intentionally reshuffles the cohort.

## Bucket calculation

1. Compute SHA-256 over the canonical payload.
2. Interpret the first four digest bytes as one unsigned 32-bit big-endian integer `u`.
3. Calculate:

```text
bucket = floor(u * 100000 / 4294967296)
```

The multiplication must use an integer type wide enough to avoid overflow. The result is always between `0` and `99,999`.

## Percentage inclusion

A rollout percentage is represented by integer units:

```text
units = percent * 1000
```

The percentage must be between `0` and `100` and have at most three decimal places.

A subject is included when:

```text
bucket < units
```

Examples:

| Percentage | Included buckets |
| --- | --- |
| `0%` | none |
| `0.001%` | `0` |
| `20%` | `0..19,999` |
| `20.001%` | `0..20,000` |
| `99.999%` | `0..99,998` |
| `100%` | `0..99,999` |

This threshold rule guarantees monotonic expansion. Increasing a rollout from `20%` to `30%` cannot remove any subject already included at `20%` when the algorithm version and canonical input fields remain unchanged.

## Conformance vectors

Machine-readable vectors are stored at:

```text
apps/control-api/src/test/resources/conformance/rollout-v1.tsv
```

The vectors include:

- ASCII targeting keys;
- composed and decomposed Unicode forms that normalize to the same NFC payload;
- CJK and emoji targeting keys;
- bucket `0`;
- bucket `19,999`, the last subject included at `20%`;
- bucket `20,000`, the first subject excluded at `20%`;
- bucket `99,999`.

Every server evaluator and SDK implementation must consume these vectors or copy them without modification. A different digest, payload, or bucket is a compatibility failure.

## Deterministic distribution sample

The test suite allocates targeting keys `sample-0` through `sample-99999` in one fixed namespace. V1 produces these decile counts:

| Bucket range | Subjects |
| --- | ---: |
| `0..9,999` | 9,945 |
| `10,000..19,999` | 9,948 |
| `20,000..29,999` | 9,890 |
| `30,000..39,999` | 10,027 |
| `40,000..49,999` | 10,041 |
| `50,000..59,999` | 10,123 |
| `60,000..69,999` | 10,063 |
| `70,000..79,999` | 9,882 |
| `80,000..89,999` | 9,904 |
| `90,000..99,999` | 10,177 |

The same sample includes 19,893 subjects at `20%`. These numbers are evidence for this deterministic sample, not a universal statistical guarantee or a production SLO.

## Compatibility and migration

The behavior of `flagforge-rollout-v1` is immutable. Refactoring, performance optimization, or a new language implementation must continue to satisfy every V1 conformance vector.

An algorithm change requires:

1. a new algorithm ID and wire version;
2. a separate conformance-vector file;
3. explicit snapshot metadata;
4. comparison tooling that shows cohort changes;
5. an operator-selected migration strategy.

Existing published snapshots remain pinned to their recorded version. A migration may publish a new revision using the new algorithm, but FlagForge must never reinterpret a V1 snapshot with V2 behavior. When preserving an existing cohort matters more than adopting a new algorithm, the environment remains on V1.

## Test strategy

The Java implementation verifies:

- byte-for-byte conformance vectors;
- deterministic repeated evaluation;
- bucket bounds;
- exact threshold boundaries;
- monotonic rollout expansion;
- Unicode NFC equivalence;
- locale-independent key normalization;
- targeting-key size limits;
- a deterministic 100,000-subject distribution sample.

Generated property sweeps use a fixed seed so failures are reproducible in local builds and CI.

---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# Alocação determinística de rollout

## Propósito

O FlagForge atribui um bucket inteiro estável a um sujeito de segmentação dentro de um namespace de alocação de uma flag. O bucket é reutilizável entre processos de avaliação, revisões publicadas e SDKs de diferentes linguagens, desde que todas as implementações usem a mesma versão de algoritmo e as mesmas entradas.

O algoritmo dá suporte a rollouts percentuais explicáveis. Ele não é um hash de senha, um verificador de credenciais, um mecanismo de anonimização nem uma fronteira de autorização.

## Identidade da versão

| Campo | Valor |
| --- | --- |
| ID do algoritmo | `flagforge-rollout-v1` |
| Versão de fio (wire) | byte sem sinal `0x01` |
| Hash | SHA-256 |
| Codificação de texto | UTF-8 |
| Normalização Unicode | NFC para a chave de segmentação |
| Faixa de buckets | `0..99.999` |
| Resolução percentual | `0,001%` |

O ID do algoritmo precisa ser persistido em todo snapshot imutável que contenha uma alocação percentual. Ele também precisa ser retornado nos contratos detalhados de avaliação e diagnóstico quando o bucket for relevante.

## Payload canônico do hash

O payload começa com estes bytes:

```text
46 46 52 41 01
 F  F  R  A  v1
```

Seis campos vêm em seguida, nesta ordem exata:

1. ID da organização;
2. ID do projeto;
3. ID do ambiente;
4. chave da flag;
5. chave de alocação;
6. chave de segmentação.

Cada campo é codificado como:

```text
comprimento em bytes UTF-8, inteiro sem sinal big-endian de 4 bytes
bytes UTF-8 do campo
```

Os prefixos de comprimento impedem que combinações ambíguas de delimitadores, como `ab|c` e `a|bc`, produzam o mesmo payload.

### Normalização de campos

- UUIDs usam a representação canônica em minúsculas com hifens produzida por `UUID.toString()`.
- Chaves de flag e de alocação têm espaços removidos nas bordas, são convertidas para minúsculas com regras independentes de locale e precisam casar com `[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?`.
- A chave de segmentação é normalizada para Unicode NFC.
- Espaços em branco no início e no fim da chave de segmentação continuam significativos e não são removidos.
- Uma chave de segmentação não pode ser vazia e é limitada a 1.024 bytes UTF-8.

A chave de alocação é um namespace estável selecionado pela configuração compilada. Mantê-la inalterada preserva a coorte ao longo de mudanças percentuais e de revisões publicadas. Alterá-la intencionalmente reembaralha a coorte.

## Cálculo do bucket

1. Calcular SHA-256 sobre o payload canônico.
2. Interpretar os quatro primeiros bytes do digest como um inteiro sem sinal de 32 bits big-endian `u`.
3. Calcular:

```text
bucket = floor(u * 100000 / 4294967296)
```

A multiplicação precisa usar um tipo inteiro largo o bastante para evitar overflow. O resultado está sempre entre `0` e `99.999`.

## Inclusão percentual

Um percentual de rollout é representado por unidades inteiras:

```text
units = percent * 1000
```

O percentual precisa estar entre `0` e `100` e ter no máximo três casas decimais.

Um sujeito é incluído quando:

```text
bucket < units
```

Exemplos:

| Percentual | Buckets incluídos |
| --- | --- |
| `0%` | nenhum |
| `0,001%` | `0` |
| `20%` | `0..19.999` |
| `20,001%` | `0..20.000` |
| `99,999%` | `0..99.998` |
| `100%` | `0..99.999` |

Essa regra de limiar garante expansão monotônica. Aumentar um rollout de `20%` para `30%` não pode remover nenhum sujeito já incluído em `20%`, desde que a versão do algoritmo e os campos canônicos de entrada permaneçam inalterados.

## Vetores de conformidade

Os vetores legíveis por máquina estão armazenados em:

```text
apps/control-api/src/test/resources/conformance/rollout-v1.tsv
```

Os vetores incluem:

- chaves de segmentação ASCII;
- formas Unicode compostas e decompostas que normalizam para o mesmo payload NFC;
- chaves de segmentação com CJK e emoji;
- bucket `0`;
- bucket `19.999`, o último sujeito incluído em `20%`;
- bucket `20.000`, o primeiro sujeito excluído em `20%`;
- bucket `99.999`.

Todo avaliador de servidor e toda implementação de SDK precisa consumir esses vetores ou copiá-los sem modificação. Um digest, payload ou bucket diferente é uma falha de compatibilidade.

## Amostra determinística de distribuição

A suíte de testes aloca as chaves de segmentação `sample-0` até `sample-99999` em um namespace fixo. A V1 produz estas contagens por decil:

| Faixa de buckets | Sujeitos |
| --- | ---: |
| `0..9.999` | 9.945 |
| `10.000..19.999` | 9.948 |
| `20.000..29.999` | 9.890 |
| `30.000..39.999` | 10.027 |
| `40.000..49.999` | 10.041 |
| `50.000..59.999` | 10.123 |
| `60.000..69.999` | 10.063 |
| `70.000..79.999` | 9.882 |
| `80.000..89.999` | 9.904 |
| `90.000..99.999` | 10.177 |

A mesma amostra inclui 19.893 sujeitos em `20%`. Esses números são evidência para esta amostra determinística, e não uma garantia estatística universal ou um SLO de produção.

## Compatibilidade e migração

O comportamento de `flagforge-rollout-v1` é imutável. Refatoração, otimização de desempenho ou uma nova implementação em outra linguagem precisam continuar satisfazendo todos os vetores de conformidade da V1.

Uma mudança de algoritmo exige:

1. um novo ID de algoritmo e uma nova versão de fio;
2. um arquivo separado de vetores de conformidade;
3. metadados explícitos no snapshot;
4. ferramental de comparação que mostre as mudanças de coorte;
5. uma estratégia de migração escolhida pelo operador.

Snapshots publicados existentes permanecem fixados à versão registrada. Uma migração pode publicar uma nova revisão usando o novo algoritmo, mas o FlagForge nunca deve reinterpretar um snapshot V1 com comportamento V2. Quando preservar uma coorte existente importa mais do que adotar um novo algoritmo, o ambiente permanece na V1.

## Estratégia de testes

A implementação Java verifica:

- vetores de conformidade byte a byte;
- avaliação repetida determinística;
- limites do bucket;
- fronteiras exatas de limiar;
- expansão monotônica de rollout;
- equivalência Unicode NFC;
- normalização de chaves independente de locale;
- limites de tamanho da chave de segmentação;
- uma amostra determinística de distribuição com 100.000 sujeitos.

As varreduras de propriedades geradas usam uma semente fixa, para que falhas sejam reprodutíveis em builds locais e na CI.

</details>
