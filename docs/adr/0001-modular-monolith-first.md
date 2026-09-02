# ADR 0001: Start as a modular monolith

- Status: Accepted
- Date: 2026-07-14

## Context

FlagForge contains distinct domains and two workloads, but the initial team and traffic do not justify distributed operational complexity. Premature services would slow domain discovery, duplicate infrastructure, and make transactions and local development harder.

## Decision

Begin as a modular monolith using explicit Java package/module boundaries, Spring Modulith verification, and ArchUnit rules. Deliver vertical slices and keep module-owned data and APIs explicit.

The Control and Evaluation planes may have separate application entry points later while continuing to share repository and domain contracts.

## Consequences

### Positive

- Faster iteration and simpler local execution.
- Strong local transactions for publication.
- Architectural boundaries can be tested before network boundaries exist.
- Extraction remains possible from stable contracts.

### Negative

- Independent scaling is unavailable initially.
- Boundary enforcement depends on tests and conventions rather than separate deployments.
- A process-level failure can affect both planes in the first deployment stage.

## Revisit when

Evaluation load, availability objectives, security isolation, or release cadence demonstrably require independent deployment.


---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# ADR 0001: Começar como monólito modular

- Status: Aceito
- Data: 2026-07-14

## Contexto

O FlagForge contém domínios distintos e duas cargas de trabalho, mas o time inicial e o tráfego não justificam a complexidade operacional de um sistema distribuído. Serviços prematuros atrasariam a descoberta do domínio, duplicariam infraestrutura e dificultariam transações e o desenvolvimento local.

## Decisão

Começar como um monólito modular usando fronteiras explícitas de pacote/módulo Java, verificação com Spring Modulith e regras de ArchUnit. Entregar fatias verticais e manter explícitos os dados e as APIs pertencentes a cada módulo.

Os planos de Controle e de Avaliação podem ter pontos de entrada de aplicação separados mais adiante, continuando a compartilhar contratos de repositório e de domínio.

## Consequências

### Positivas

- Iteração mais rápida e execução local mais simples.
- Transações locais fortes para a publicação.
- As fronteiras arquiteturais podem ser testadas antes de existirem fronteiras de rede.
- A extração permanece possível a partir de contratos estáveis.

### Negativas

- Escalabilidade independente fica indisponível inicialmente.
- A imposição de fronteiras depende de testes e convenções, e não de deploys separados.
- Uma falha em nível de processo pode afetar ambos os planos no primeiro estágio de deploy.

## Revisitar quando

A carga de avaliação, os objetivos de disponibilidade, o isolamento de segurança ou a cadência de lançamento exigirem comprovadamente deploy independente.

</details>
