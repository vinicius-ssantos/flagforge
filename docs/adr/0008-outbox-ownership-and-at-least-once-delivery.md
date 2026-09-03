# ADR 0008: Move outbox ownership to distribution and deliver at least once

- Status: Accepted
- Date: 2026-09-02

## Context

Publication wrote its own rows into `configuration_outbox` and nothing consumed them. Building the
relay forced a question the schema had left open: which module owns delivery state?

Two rules pointed in opposite directions. `AGENTS.md` forbids a module from touching another
module's tables, so a relay living in `distribution` could not read an outbox owned by
`publishing`. But `docs/ARCHITECTURE.md` already assigns "outbox and delivery state" to
`distribution`, so a relay living in `publishing` would contradict the documented boundary and put
delivery concerns inside the module that must stay focused on correctness of publication.

## Decision

`distribution` owns the outbox. `publishing` records events through `ConfigurationEventOutbox`, a
public contract, instead of inserting rows itself.

That contract is `@Transactional(propagation = MANDATORY)`, the same mechanism `AuditTrailService`
uses. An event can only be recorded inside the transaction that produced the revision it describes,
so the event and the publication commit or roll back together. Recording an event for a publication
that never happened is not merely discouraged; it fails.

Delivery is **at least once**. An event is marked delivered only after every listener accepted it,
so a failure between delivering and marking replays rather than loses. Consumers are therefore
required to be idempotent and to compare versions rather than trust arrival order.

The relay claims work with `FOR UPDATE SKIP LOCKED`, which lets replicas share one backlog without
delivering the same event twice — the deployment stage the architecture already plans for.

Two triggers with different jobs: a post-commit hook so propagation does not wait for a poll, and a
fixed-delay poll that recovers anything whose trigger was lost to a crash. The trigger is an
optimisation; correctness rests on the poll.

A transient listener failure reschedules the event with exponential backoff. After a configured
number of attempts the event becomes `FAILED` and stops being claimed, so one poisoned event cannot
occupy the relay forever.

## Consequences

### Positive

- Delivery state lives behind one boundary, and `ApplicationModules.verify()` enforces it.
- Publication cannot create an event that outlives a rolled-back transaction.
- The relay is replica-safe from the first version rather than after a later migration.
- A failing consumer degrades distribution only; revisions, snapshots, and the pointer are untouched.
- `#19` plugs Redis into `ConfigurationChangeListener` without touching publication.

### Negative

- `PublicationService` now depends on `distribution`, a dependency that did not exist.
- At-least-once pushes idempotency onto every consumer, forever.
- An abandoned event needs an operator: nothing retries a `FAILED` row automatically.
- Delivery happens inside the relay's own transaction, so a slow listener holds a database
  connection. In-process listeners make that cheap now; a listener doing network I/O would make it
  worth revisiting.
- The relay is in-process, so it scales with the application rather than independently.

## Alternatives considered

**Leave the insert in publishing and let distribution read the table.** The smallest change, and the
one that quietly breaks the rule that makes module boundaries mean anything. Rejected because the
architecture test would either fail or have to be weakened.

**Spring Modulith's event publication registry.** It solves the same problem and is already a
dependency family in use. Rejected because the outbox table, its constraints, and its partial index
already exist and model this exact lifecycle; adopting the registry would mean carrying two
mechanisms or migrating away from a schema that is not wrong.

**Exactly-once delivery.** Not achievable across a database commit and an external consumer without
distributed transactions. Pretending otherwise would push a guarantee into consumers that the system
cannot keep.

## Revisit when

- A consumer performs network I/O during delivery, making the transaction-held connection costly.
- Delivery latency or throughput justifies a broker, which would supersede the relay rather than
  extend it.
- Abandoned events become frequent enough to need an automatic reprocessing path.

---

<details>
<summary><strong>🇧🇷 Português (pt-BR)</strong></summary>

# ADR 0008: Mover a propriedade do outbox para distribution e entregar ao menos uma vez

- Status: Aceito
- Data: 2026-09-02

## Contexto

A publicação gravava suas próprias linhas em `configuration_outbox` e nada as consumia. Construir o
relay forçou uma pergunta que o schema havia deixado em aberto: qual módulo possui o estado de
entrega?

Duas regras apontavam em direções opostas. O `AGENTS.md` proíbe um módulo de tocar as tabelas de
outro, então um relay morando em `distribution` não poderia ler um outbox pertencente a
`publishing`. Mas a `docs/ARCHITECTURE.md` já atribui "outbox and delivery state" a `distribution`,
de modo que um relay morando em `publishing` contrariaria a fronteira documentada e colocaria
preocupações de entrega dentro do módulo que precisa permanecer focado na correção da publicação.

## Decisão

O `distribution` possui o outbox. O `publishing` registra eventos por `ConfigurationEventOutbox`, um
contrato público, em vez de inserir linhas ele mesmo.

Esse contrato é `@Transactional(propagation = MANDATORY)`, o mesmo mecanismo que o
`AuditTrailService` usa. Um evento só pode ser registrado dentro da transação que produziu a revisão
que ele descreve, de modo que evento e publicação são confirmados ou revertidos juntos. Registrar um
evento de uma publicação que nunca aconteceu não é apenas desencorajado; falha.

A entrega é **ao menos uma vez**. Um evento só é marcado como entregue depois que todos os listeners
o aceitaram, então uma falha entre entregar e marcar reexecuta em vez de perder. Os consumidores,
portanto, precisam ser idempotentes e comparar versões, em vez de confiar na ordem de chegada.

O relay reivindica trabalho com `FOR UPDATE SKIP LOCKED`, o que permite que réplicas compartilhem uma
fila sem entregar o mesmo evento duas vezes — o estágio de deploy que a arquitetura já prevê.

Dois gatilhos com funções diferentes: um hook pós-commit para que a propagação não espere um poll, e
um poll de intervalo fixo que recupera qualquer coisa cujo gatilho se perdeu num crash. O gatilho é
otimização; a correção repousa no poll.

Uma falha transitória de listener reagenda o evento com backoff exponencial. Após um número
configurado de tentativas, o evento vira `FAILED` e deixa de ser reivindicado, para que um evento
envenenado não ocupe o relay indefinidamente.

## Consequências

### Positivas

- O estado de entrega vive atrás de uma fronteira, e o `ApplicationModules.verify()` a impõe.
- A publicação não consegue criar um evento que sobreviva a uma transação revertida.
- O relay é seguro para réplicas desde a primeira versão, e não após uma migração posterior.
- Um consumidor com falha degrada apenas a distribuição; revisões, snapshots e ponteiro ficam intactos.
- A `#19` pluga o Redis em `ConfigurationChangeListener` sem tocar na publicação.

### Negativas

- O `PublicationService` passa a depender de `distribution`, dependência que não existia.
- Entrega ao menos uma vez empurra idempotência para todo consumidor, para sempre.
- Um evento abandonado exige um operador: nada reprocessa uma linha `FAILED` automaticamente.
- A entrega acontece dentro da transação do próprio relay, então um listener lento segura uma conexão
  de banco. Listeners em processo tornam isso barato agora; um listener fazendo I/O de rede tornaria
  isso digno de revisão.
- O relay é em processo, então escala junto com a aplicação, e não de forma independente.

## Alternativas consideradas

**Deixar o insert em publishing e permitir que distribution leia a tabela.** A menor mudança, e a que
quebra silenciosamente a regra que dá sentido às fronteiras de módulo. Rejeitada porque o teste de
arquitetura ou falharia ou teria de ser enfraquecido.

**O registro de publicação de eventos do Spring Modulith.** Resolve o mesmo problema e já é uma
família de dependências em uso. Rejeitada porque a tabela de outbox, suas constraints e seu índice
parcial já existem e modelam exatamente este ciclo de vida; adotar o registro significaria carregar
dois mecanismos ou migrar para longe de um schema que não está errado.

**Entrega exatamente uma vez.** Inatingível entre um commit de banco e um consumidor externo sem
transações distribuídas. Fingir o contrário empurraria para os consumidores uma garantia que o
sistema não consegue cumprir.

## Revisitar quando

- Um consumidor fizer I/O de rede durante a entrega, tornando cara a conexão presa pela transação.
- Latência ou throughput de entrega justificarem um broker, que substituiria o relay em vez de
  estendê-lo.
- Eventos abandonados se tornarem frequentes o bastante para exigir reprocessamento automático.

</details>
