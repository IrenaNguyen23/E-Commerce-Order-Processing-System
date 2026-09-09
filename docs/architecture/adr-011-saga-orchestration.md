# ADR-011 — How the order saga is orchestrated

**Status:** Accepted
**Details:** ADR-004 (Kafka Orchestration) in `.github/docs/architecture/ARCHITECTURE_DECISIONS.md`
**Date:** 2026-08-26

---

## Context

ADR-004 names orchestration as the saga pattern. It does not say how, and "how" is where every
interesting decision lives: where the coordinator runs, what a command looks like, what happens
when a participant does not answer, and what a participant owes the coordinator in return. This
ADR records those.

There is history worth keeping, because it is the argument for the design. The repository was
first built to a choreography reading of ADR-004: every service reacted to the facts the others
published, and the order flow emerged from the sum of those subscriptions. That worked, and it had
the properties choreography is chosen for — no service could block another, and any of them could
be down for a while without the others noticing.

It also had the costs choreography is known for, and this repository ran into all three. They are
the reason the details below are what they are:

**The flow existed nowhere.** To answer "what happens after payment succeeds" you had to open four
services and read their `@KafkaListener` annotations. `docs/architecture/saga.md` described the
sequence in prose, and prose drifts from code silently. Adding a step meant editing whichever
service happened to publish the event before it, which is not where anyone would think to look.

**Nobody was holding a clock.** A participant that went quiet produced no signal beyond an order
that stayed in one status. `StalledSagaMonitor` scanned for orders that had not moved in fifteen
minutes and raised a gauge — but it could not say *which step* was late, and it could not do
anything about it, because nothing in the system knew what had been asked of whom.

**Compensation was a subscription, not a decision.** Inventory released stock because it happened
to be listening to `payment.failed`, and defensively to `order.cancelled` as well, in case the
first one did not arrive. Each participant carried a piece of the recovery logic and none of them
could see the whole. Whether the right things had been undone was not a question the system could
answer.

## Decision

The order flow is driven by an explicit orchestrator, on these terms.

**Commands and replies replace facts.** The orchestrator sends a command to one named participant
(`inventory.commands`, `payment.commands`, `notification.send`); the participant does the work and
answers on its reply topic. No participant subscribes to another participant's topic. The
imperative/past-tense split in the message names makes the direction of any message obvious from a
topic dump.

**The orchestrator lives in Order Service**, in its own `saga` package, not in a separate
deployment. It advances the saga and the order aggregate in one transaction against one database,
so the two can never disagree about whether an order was cancelled. A standalone orchestrator
service would buy independent scaling and pay for it with exactly the split-brain the pattern
exists to prevent. The orchestrator is decoupled from Order Service's other code by package and by
interface, so extracting it later is a move, not a rewrite.

**The saga is persisted.** `saga_instance` holds the live position — current step, outstanding
command, deadline, and the handles compensation will need. `saga_step_log` is the append-and-close
history: one row per command sent, closed by the reply that answered it.

**Every step has a deadline and a retry budget.** An unanswered command is re-sent up to
`max-attempts`; after that the saga is parked as `STALLED` for an operator. It is deliberately not
compensated automatically — a payment step that has gone quiet may well have taken the money, and
cancelling the order on that guess is a worse failure than the outage that caused it.

**Participants must always reply**, including for work they have already done. A re-sent
`RESERVE_INVENTORY` for an order that is already reserved answers `inventory.reserved` again, with
the original reservation id. This is the contract that makes retry safe, and it is the one thing
most likely to be got wrong by someone adding a participant: noticing the duplicate and returning
quietly deadlocks the saga, because the orchestrator re-sent precisely because no answer arrived.
Silence is not idempotence.

## What this costs

Stated plainly, because the choreography advocates are not wrong about any of it:

| Cost | How it is handled here |
|---|---|
| The orchestrator is a single point of coordination | It is stateless between messages; state is in Postgres. Several replicas consume the reply topics in one group. |
| A step failing to reply now blocks the saga | Which is honest: it always did. The difference is that it is now visible, deadlined, and retried. |
| More messages on the wire | Roughly double: a command and a reply where choreography had one fact. Kafka is not the constraint at this scale. |
| Participants must implement the reply rule | Documented on each participant class, and each has a unit test that fails if it goes quiet. |
| Business logic risks drifting into the orchestrator | The orchestrator decides *sequence*, never *outcome*. Whether a card is declined is Payment's decision; what to do about it is the orchestrator's. |

## Consequences

`order.created`, `order.completed` and `order.cancelled` are still published, and are still part of
the public integration contract, but **no saga participant consumes them any more**. They are
domain events for observers — analytics, BI, anything downstream — and the saga does not depend on
anyone reading them.

`StalledSagaMonitor` — the choreography-era watchdog that scanned for orders which had not moved —
is replaced by `SagaTimeoutMonitor`, and the alert changes with it: the metric is no longer "orders
that have not moved" but "sagas that stopped answering", which is a different and far more
actionable statement.

The order state machine is unchanged. `CREATED → INVENTORY_RESERVED → PAID → COMPLETED` and the
`CANCELLED` branch mean exactly what they meant before, so the REST API and the frontend needed no
change.

## Migration from the choreography implementation

The command topics are additive; the reply topics keep their names. A deployment must roll out in
this order, because a participant that does not understand commands will dead-letter them:

1. Deploy the participants (Inventory, Payment, Notification). They now listen to their command
   topics as well as their old fact subscriptions being gone — safe, because the orchestrator is
   not sending yet.
2. Deploy Order Service, which runs the Flyway migration creating `saga_instance` and
   `saga_step_log`, and starts issuing commands.

In-flight sagas do not migrate. Orders that were mid-flow at cutover have no `saga_instance` row
and will not advance; drain them before deploying, or complete them by hand. This is stated rather
than automated because a fabricated saga row for an order whose real position is unknown is worse
than an order an operator has to look at.
