# The order saga

How a single `POST /api/orders` becomes a completed — or cleanly cancelled — order across four
services, with no distributed transaction and one component that knows the whole flow.

Implements ADR-004 (Kafka orchestration) as detailed by
[ADR-011](adr-011-saga-orchestration.md), plus ADR-005 (database per service) and
ADR-006 (outbox).

---

## 1. Where the flow lives

In one file: [`SagaStep`](../../services/order-service/src/main/java/com/commerceflow/orderservice/saga/entity/SagaStep.java).

```java
RESERVE_INVENTORY  ─▶  PROCESS_PAYMENT  ─▶  CONFIRM_INVENTORY  ─▶  NOTIFY_CUSTOMER
RELEASE_INVENTORY  (compensation — undoes RESERVE_INVENTORY)
```

That is the sequence, and changing the order of the saga means editing that enum and the
orchestrator that walks it. Nowhere else in the codebase does the order of the steps appear, so
this document cannot drift from the flow without the drift being obvious.

**The orchestrator sends commands and waits for replies.** A participant is told what to do; it
does it and answers. It does not know what came before it, what comes after it, or whether the
order will ultimately succeed.

The comparison below is against the choreography implementation this replaced — kept because it is
the argument for every design choice further down, and because the trade is real in both
directions.

| | Choreography | Orchestration (ADR-011) |
|---|---|---|
| Who decides the next step | Whoever subscribed to the last event | The orchestrator |
| Where the flow is written down | Nowhere; inferred from subscriptions | `SagaStep` + `OrderSagaOrchestrator` |
| Who holds the clock | Nobody | The orchestrator, per step |
| Compensation | Each participant's own subscription | Commanded explicitly, in reverse |
| Adding a step | Edit whichever service published the event before it | Edit the orchestrator |
| Cost | — | One more component; roughly double the messages |

---

## 2. The happy path

```
  Customer
     │  POST /api/orders                      (synchronous, returns 201 CREATED)
     ▼
┌──────────────────────────────────────────────┐
│  Order Service                               │  one transaction:
│  ┌────────────────────────────────────────┐  │  orders + order_read_model
│  │  ORCHESTRATOR                          │  │  + saga_instance + saga_step_log
│  └────────────────────────────────────────┘  │  + outbox(RESERVE_INVENTORY)
└───────┬──────────────────────────────────────┘
        │ ① RESERVE_INVENTORY ──▶ inventory.commands
        │                                              ┌──────────────┐
        │                                              │  Inventory   │ available -= qty
        │ ◀── inventory.reserved ───────────────────── │              │ reserved  += qty
        │                                              └──────────────┘
        │    order → INVENTORY_RESERVED
        │
        │ ② PROCESS_PAYMENT ────▶ payment.commands
        │                                              ┌──────────────┐
        │ ◀── payment.completed ─────────────────────  │   Payment    │ charges the card
        │                                              └──────────────┘
        │    order → PAID
        │
        │ ③ CONFIRM_INVENTORY ──▶ inventory.commands
        │                                              ┌──────────────┐
        │ ◀── inventory.confirmed ─────────────────── │  Inventory   │ reserved -= qty
        │                                              └──────────────┘   (permanent)
        │    order → COMPLETED,  publishes order.completed
        │
        │ ④ NOTIFICATION_SEND ──▶ notification.send
        │                                              ┌──────────────┐
        │ ◀── notification.sent ────────────────────── │ Notification │ emails the customer
        │                                              └──────────────┘
        │    saga → COMPLETED
        ▼
      (done)
```

Two details worth noticing:

**The order does not complete when the card is charged.** It becomes `PAID`, and only reaches
`COMPLETED` once the stock has actually been written off. Saying "completed" while a step is still
outstanding would be a lie the customer can see.

**`order.created`, `order.completed` and `order.cancelled` are still published**, and are still
part of the public integration contract. No saga participant consumes them. They exist for
observers — analytics, BI, Kafka UI during a demo — and the saga does not depend on anyone reading
them.

---

## 3. The compensation paths

### 3a. Stock cannot be reserved

```
① RESERVE_INVENTORY ──▶ Inventory   (nothing moves)
  ◀── inventory.failed
     order → CANCELLED (failedStep = INVENTORY), publishes order.cancelled
④ NOTIFICATION_SEND ──▶ Notification
  ◀── notification.sent
     saga → COMPENSATED
```

Nothing to compensate: the reservation never happened. This is why Inventory is the *first* step —
the cheapest failure is the one that happens before any money moves. Which steps have to be undone
is a fact the orchestrator holds, so no release is sent — rather than each participant having to
work out for itself whether a compensation it receives applies to anything it is holding.

### 3b. Payment is declined

```
② PROCESS_PAYMENT ──▶ Payment       (charge declined)
  ◀── payment.failed
     order → CANCELLED (failedStep = PAYMENT), publishes order.cancelled
③ RELEASE_INVENTORY ──▶ Inventory   reserved -= qty, available += qty
  ◀── inventory.released
④ NOTIFICATION_SEND ──▶ Notification
  ◀── notification.sent
     saga → COMPENSATED
```

The order is cancelled at the moment the decision is made, not after the cleanup finishes. The
customer's screen should not keep saying "processing" while an internal release runs.

---

## 4. State machines

### The order

```
              ┌───────────── inventory.failed ────────────────────────┐
              │                                                       │
              │        ┌────── payment.failed ──────┐                 │
              │        │                            │                 ▼
   CREATED ───┴─▶ INVENTORY_RESERVED ──payment──▶ PAID ──confirm──▶ COMPLETED
      │                                                              (terminal)
      └──────────────────────────────────────────────────────────▶ CANCELLED
                                                                    (terminal)
```

`OrderStatus.canTransitionTo` enforces this in code. Unchanged by the move to orchestration, which
is why the REST API and the frontend needed no change.

`failedStep` on the order is the **customer-facing** name of the step — `INVENTORY`, `PAYMENT` or
`NOTIFICATION` — not the orchestrator's internal `SagaStep`. It is a published API enum, so three
orchestrator steps collapse onto `INVENTORY`: a customer does not need to know whether their order
stopped at the reserve, the confirm or the release, and renaming or splitting a step must never
break a client. `SagaStep.domain()` is the one place that mapping lives.

### The saga

```
   STARTED ──── every forward step succeeded ────▶ COMPLETED   (terminal)
      │
      │ a step replied with a failure
      ▼
  COMPENSATING ── every undo acknowledged ──────▶ COMPENSATED  (terminal)
      │
      │ a step stopped answering and the retries ran out
      ▼
   STALLED  (parked for an operator; resumable)
```

---

## 5. What makes it correct

### 5a. Everything commits together

Each handler is one transaction spanning four things: the idempotency claim, the saga row, the
order aggregate with its read-model projection, and the outbox row carrying the next command. They
commit together or not at all, so a crash at any point leaves the state and the instruction that
follows from it in agreement.

### 5b. The outbox (ADR-006)

No command or reply is published directly. Every one is written into an `outbox_event` row in the
same transaction as the state change; a background relay claims batches with
`FOR UPDATE SKIP LOCKED` — so several replicas relay concurrently without ever publishing the same
row twice — and marks them `PUBLISHED` once the broker acknowledges.

`OutboxService.append` is `Propagation.MANDATORY`: appending outside a transaction fails fast at
development time rather than silently losing messages in production.

### 5c. The idempotency ledger

Delivery is at-least-once, so every handler starts by claiming the message:

```java
if (!idempotency.claim(GROUP, message.getEventId(), message.getEventType())) {
    return;                       // already handled; the reply is already in the outbox
}
```

The claim row is written in the same transaction as the business change, so either both survive or
neither does. A concurrent duplicate loses the primary-key race, its transaction rolls back, Kafka
redelivers, and the redelivery then sees the committed claim and is dropped.

**Outbox (at-least-once) + ledger (dedupe) = effectively-once processing.** That is the whole
correctness argument for delivery, and it is worth being able to state in one line.

### 5d. The step guard

The ledger dedupes by message id. It cannot help with a *late* reply to a step the saga has since
moved past — a `payment.failed` that arrives after the order completed, say. So the orchestrator
adds one rule:

> **A reply is acted on only when it names the step the saga is waiting for.**

Everything else is dropped with a log line. A late failure cannot cancel a paid order, because by
then the saga is on a different step.

A reply *is* accepted when its `causationId` points at an earlier attempt of the current step. The
step is answered either way, and refusing a slow first reply in favour of a re-send that may never
come would trade a working saga for a tidier audit trail.

### 5e. The reply rule

> **Every command produces exactly one reply, including a command for work already done.**

A re-sent `RESERVE_INVENTORY` for an order that is already reserved answers `inventory.reserved`
again, with the original reservation id. A `RELEASE_INVENTORY` for an order with no reservation
answers `inventory.released` — a compensation with nothing to undo has still succeeded.

This is the contract that makes the timeout retry safe, and it is the thing most likely to be got
wrong when adding a participant. The tempting alternative — notice the duplicate, return quietly —
deadlocks the saga: the orchestrator re-sent *precisely because* no answer arrived, so staying
silent the second time guarantees it never will. **Silence is not idempotence.**

There is exactly one deliberate exception, in Payment: a payment row stuck in `PENDING` has no safe
answer, because the money may or may not have moved. Guessing either way risks cancelling a paid
order or completing an unpaid one, so that case stays silent and is escalated to a human by the
timeout below.

### 5f. Ordering

Every message is keyed by order id, so all messages about one order land on the same partition and
are processed in order. Different orders proceed in parallel across partitions.

The three inventory commands share `inventory.commands` for the same reason: one topic, one key,
so a release can never overtake the reserve it is compensating. Splitting them across topics would
reintroduce that race.

### 5g. Poison messages

`DefaultErrorHandler` retries transient failures with an exponential back-off and then routes the
record to `<topic>.DLT`. Domain rule violations, malformed payloads and unrecognised command types
are marked non-retryable and go straight there: replaying them cannot change the outcome, and
retrying forever would block the partition — and therefore every other order that hashes to it.

---

## 5b. Cancelling an order

A cancellation is not a failure — nothing went wrong, somebody changed their mind — but it unwinds
the same way, through the same orchestrator and the same step log. The saga is **reopened** rather
than a second one started: there is one flow per order, and its history should read as one story.

```
             ┌── paid ──▶ REFUND_PAYMENT ─┐
cancel ──────┤                            ├──▶ RELEASE or RESTOCK ──▶ NOTIFY_CUSTOMER
             └── unpaid ──────────────────┘
```

**The order is cancelled immediately, before any of the undoing.** It will not be fulfilled, and
that is knowable now; making a customer watch "processing" while a refund clears would report our
internal progress as if it were their order's status. Whether the money is back is the *payment's*
status, and the payment says so itself — which is why there is no `OrderStatus.REFUNDED`.

**Money before goods.** A customer chasing a refund is a worse outcome than a unit that reappears a
second late, so the refund goes first and the stock follows its reply.

**Release and restock are not the same operation**, and picking the wrong one is silent:

| Order reached | Stock is | Undo | Getting it wrong |
|---|---|---|---|
| `PAID` or earlier | On hold | `RELEASE_INVENTORY` | A restock credits units that were never deducted |
| `COMPLETED` | Written off as sold | `RESTOCK_INVENTORY` | A release finds no hold and the units stay gone |

Which one is needed is decided when the cancellation is requested and stored on the saga
(`stock_undo`), because cancelling overwrites the order's status and the old value is gone.

**A failed refund does not stop the saga.** The order is already cancelled and the customer is
already owed; halting there would strand the stock as well as the money. The refund failure is
logged as an error, the payment stays `COMPLETED` so the debt is visible, and the goods still come
home.

**Cancelling into a running saga is refused.** An order mid-flight has a command outstanding, and
reopening the saga would put a second one against the same step — the pending reply is then dropped
by the step guard, which is harmless until the reply it dropped was "payment succeeded" and a refund
has already gone out for a charge nothing recorded. The API answers `409` and says to try again in
a moment, because it settles in seconds. A *parked* saga is the exception: it has already stopped,
so an administrator may cancel it, having checked what the payment step was doing.

---

## 6. When a step does not answer

This is the part of the pattern that only a coordinator can provide.

Every command sets a deadline (`commerceflow.order.saga.step-timeout`, default 2 minutes).
`SagaTimeoutMonitor` sweeps for overdue steps every 30 seconds. Under the retry budget it re-sends
the command and resets the deadline — safe because of the reply rule, counted as
`commerceflow.saga.step.resent`.

When the budget runs out (`max-attempts`, default 3), what happens depends on **the step**, and
the question that decides it is always the same: *could money have moved by now?*

| Step | Policy | Why |
|---|---|---|
| `RESERVE_INVENTORY` | **Abandon** | No payment command was ever sent, so nothing can have been charged. Cancel the order, release the stock, tell the customer. |
| `PROCESS_PAYMENT` | **Park** | The charge may or may not have happened. Releasing would oversell; completing would ship unpaid goods. A human decides. |
| `CONFIRM_INVENTORY` | **Park** | The customer was charged, so holding the stock is *correct* — it is sold. Only the write-off is outstanding. |
| `RELEASE_INVENTORY` | **Keep trying** | The decision to undo is already made and recorded. Giving up here is exactly what strands stock for an order that was cancelled. |
| `NOTIFY_CUSTOMER` | **Park** | Everything is settled; only the email is outstanding, and nothing is held. |

That table lives in code, in `SagaStep.onRetriesExhausted()`, and `ExhaustionPolicyTest` pins every
entry — including the rule that **only steps before the payment command may abandon**, so a step
added later cannot quietly opt into unwinding after money has moved.

Parking everything was the first implementation, and it was wrong in two expensive, silent ways: a
parked reserve held stock for an order that would never be placed, and a parked release held stock
for an order that had already been cancelled. Neither raised an error. Both made the shop smaller
than it thought it was.

Abandoning is counted as `commerceflow.saga.abandoned`. It is handled, but every one of them is a
customer who did not get their order, so it should sit near zero.

Each saga is chased in its own transaction and its own try/catch, so one poisoned row cannot stop
the other ninety-nine from recovering. Losing the optimistic-lock race against a reply that lands
mid-sweep is normal and is logged at `warn`, not `error` — the reply won, and there was nothing to
time out.

---

## 7. Message reference

### Commands — orchestrator to one participant

| Topic | Command | Recipient | Answered by |
|---|---|---|---|
| `inventory.commands` | `RESERVE_INVENTORY` | Inventory | `inventory.reserved` / `inventory.failed` |
| `inventory.commands` | `RELEASE_INVENTORY` | Inventory | `inventory.released` |
| `inventory.commands` | `CONFIRM_INVENTORY` | Inventory | `inventory.confirmed` |
| `inventory.commands` | `RESTOCK_INVENTORY` | Inventory | `inventory.restocked` |
| `payment.commands` | `REFUND_PAYMENT` | Payment | `payment.refunded` |
| `payment.commands` | `PROCESS_PAYMENT` | Payment | `payment.completed` / `payment.failed` |
| `notification.send` | `NOTIFICATION_SEND` | Notification | `notification.sent` |

### Replies — participant to orchestrator

| Topic | Meaning |
|---|---|
| `inventory.reserved` | Stock is held; carries the `reservationId` the compensation will need |
| `inventory.failed` | Stock could not be held |
| `inventory.released` | A hold was compensated |
| `inventory.confirmed` | A hold became a permanent deduction |
| `payment.completed` | The customer was charged |
| `payment.failed` | The charge was declined |
| `payment.refunded` | The money went back, or could not be — carries a `success` flag |
| `inventory.restocked` | Sold goods are back on the shelf |
| `notification.sent` | The customer was told (or delivery permanently failed) |

### Domain events — nobody in the saga listens

| Topic | Published by | For |
|---|---|---|
| `order.created` | Order | External observers, analytics |
| `order.completed` | Order | Same |
| `order.cancelled` | Order | Same |

### The envelope

Every message carries `eventId`, `eventType`, `timestamp`, `correlationId`, and two fields that
orchestration adds:

- **`sagaId`** — which saga this belongs to. Stamped by the orchestrator on every command and
  echoed unchanged on the reply. A message without one is not part of an order saga and is dropped
  by the orchestrator: `notification.send` also carries Auth Service's welcome mail, and that is
  exactly how it is told apart.
- **`causationId`** — the `eventId` of the command being answered. Links the two halves of a step
  in the audit trail and lets a reply to a superseded attempt close its own row rather than
  crediting the latest one.

Beyond the envelope, each message carries enough business state for its recipient to act **without
calling back** — the payable amount travels on `PROCESS_PAYMENT`, the customer email on every
notification command. A participant that has to call back is a participant that can be blocked by
the service it calls.

The `__TypeId__` header carries the logical name (`RESERVE_INVENTORY`), not a Java class name, so
the wire contract survives refactoring and stays consumable by non-JVM clients. It is also what
routes the three commands on `inventory.commands` to the right `@KafkaHandler`.

---

## 8. Failure matrix

| What fails | What happens | Recovery |
|---|---|---|
| Order Service dies after commit, before relay | The command sits `PENDING` in the outbox | The next poll, on any replica, publishes it |
| Kafka is unreachable | Rows accumulate as `PENDING`, `attempts` climbs | Automatic once the broker returns; rows past `maxAttempts` are parked `FAILED` for an operator |
| Inventory is down | The reserve step goes unanswered | Re-sent on each deadline; parked as `STALLED` after the budget, naming the exact step |
| Payment is down | Stock stays reserved, the order sits in `INVENTORY_RESERVED` | Same. The step log says which command is outstanding and since when |
| Payment charges, then crashes before replying | The reply survives the crash with the payment | The relay publishes on restart; a re-sent command answers from the existing payment; the customer is never charged twice (unique index on `order_id`) |
| Payment is stuck `PENDING` | No reply, on purpose | The saga times out and parks. An operator reconciles against the acquirer |
| The acquirer times out | Recorded as `GATEWAY_ERROR`, answered as `payment.failed` | The orchestrator compensates; an operator reconciles |
| Notification cannot deliver | Recorded `FAILED`, answered anyway with `status=FAILED` | The saga still terminates; a bounced email never leaves a paid order looking unfinished |
| A reply arrives for a step already passed | Dropped by the step guard, logged at `info` | Nothing to do; this is the mechanism working |
| A participant gets a poison message | Retried with back-off, then dead-lettered | The partition keeps moving; the DLT is inspected by hand |
| Two customers race for the last unit | `PESSIMISTIC_WRITE` in product-id order serialises them | One reserves, the other's saga compensates |
| The orchestrator crashes mid-saga | Nothing is lost: position is in `saga_instance`, the next command is in the outbox | Any replica picks up the reply topics; overdue steps are swept |

---

## 9. Following a saga in production

The first question is now a single query.

```sql
-- Where is this order, and what is it waiting for?
SELECT state, current_step, attempt, step_deadline, failed_step, failure_reason
  FROM saga_instance WHERE id = '<order-id>';

-- Everything that happened to it, in order, with the latency of each step.
SELECT step, compensation, attempt, status, detail,
       created_at, completed_at, completed_at - created_at AS latency
  FROM saga_step_log
 WHERE saga_id = '<order-id>'
 ORDER BY created_at;

-- What is parked and needs a human?
SELECT id, order_number, current_step, attempt, updated_at
  FROM saga_instance WHERE state = 'STALLED' ORDER BY updated_at;
```

The correlation id still works, and is still stamped by the gateway and propagated over HTTP and
Kafka headers — it is how you follow one saga through the *logs* of the participants, which the
step log cannot show you.

```bash
kubectl -n commerceflow logs -l app.kubernetes.io/part-of=commerceflow --tail=-1 \
  | grep '"correlationId":"3f1c…"' | sort -t'"' -k4
```

```sql
-- Did a command ever leave Order Service?
SELECT event_type, topic, status, attempts, created_at, published_at, last_error
  FROM outbox_event WHERE aggregate_id = '<order-id>' ORDER BY created_at;

-- Did the participant already handle it?
SELECT * FROM processed_event WHERE event_id = '<command-id>';
```

```bash
# Anything parked on a dead-letter topic?
kafka-console-consumer --bootstrap-server kafka:9092 \
  --topic inventory.commands.DLT --from-beginning --max-messages 10
```

---

## 10. Adding a participant

1. Add the step to `SagaStep`, and the command and reply types under `common/event`.
2. Register both in `KafkaTypeMappings`, with their topics in `KafkaTopics`.
3. Add the handler to `OrderSagaOrchestrator`: which reply advances to it, and which command it
   sends next. If the step needs undoing, add its compensation there too.
4. In the participant, listen to your command topic **only** — never to another participant's
   reply topic. If you find yourself wanting to, the flow decision you are making belongs in the
   orchestrator.
5. **Implement the reply rule**, and write the test that proves it: send the same command twice
   and assert two replies with one side effect.
