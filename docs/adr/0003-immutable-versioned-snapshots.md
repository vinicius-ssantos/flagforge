# ADR 0003: Evaluate immutable versioned snapshots

- Status: Accepted
- Date: 2026-07-14

## Context

Evaluating flags directly from mutable normalized tables can expose partially updated rules, segments, or prerequisites. It also makes caching and rollback ambiguous.

## Decision

Each successful publication compiles a complete immutable snapshot for one project environment. The snapshot has a monotonic version, algorithm version, checksum, and all data required for evaluation.

Evaluators answer from exactly one snapshot version. Rollback creates a new publication based on a previous snapshot.

## Consequences

### Positive

- Atomic evaluation view.
- Straightforward cache keys and version comparison.
- Reproducible historical evaluation and rollback.
- SDK conformance can be tested against fixed snapshots.

### Negative

- Snapshot storage duplicates normalized data.
- Compilation and compatibility require versioning.
- Large environments may require compression or incremental transport later.


---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# ADR 0003: Avaliar snapshots versionados imutáveis

- Status: Aceito
- Data: 2026-07-14

## Contexto

Avaliar flags diretamente a partir de tabelas normalizadas mutáveis pode expor regras, segmentos ou pré-requisitos parcialmente atualizados. Isso também torna o cache e o rollback ambíguos.

## Decisão

Cada publicação bem-sucedida compila um snapshot imutável completo para um ambiente de projeto. O snapshot tem versão monotônica, versão de algoritmo, checksum e todos os dados necessários para a avaliação.

Os avaliadores respondem a partir de exatamente uma versão de snapshot. O rollback cria uma nova publicação baseada em um snapshot anterior.

## Consequências

### Positivas

- Visão de avaliação atômica.
- Chaves de cache e comparação de versões diretas.
- Avaliação histórica reprodutível e rollback.
- A conformidade dos SDKs pode ser testada contra snapshots fixos.

### Negativas

- O armazenamento de snapshots duplica dados normalizados.
- A compilação e a compatibilidade exigem versionamento.
- Ambientes grandes podem exigir compressão ou transporte incremental mais adiante.

</details>
