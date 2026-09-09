# CommerceFlow

Microservices-based e-commerce order processing. Six Spring Boot 3.4 services on Java 21, an
orchestrated Kafka saga, database per service, and the operational scaffolding to run it.

Built to the architecture documents in [.github/docs/](.github/docs/) — the ADRs, contracts and
conventions there are the specification; this README is the entry point.

---

## What it does

A customer places an order. An orchestrator in Order Service then drives four services through it,
asynchronously and without a distributed transaction, to a completed order or a cleanly undone one:

```
              ┌─────────────────────────────────────────────┐
              │  ORCHESTRATOR  (Order Service)              │
              └──┬────────────┬────────────┬────────────┬───┘
    ① reserve    │  ② charge  │ ③ confirm  │  ④ notify  │
                 ▼            ▼            ▼            ▼
             Inventory     Payment     Inventory   Notification
                 │            │
   out of stock ─┘            └─ declined ──▶ ③ release stock, ④ notify
        └──▶ ④ notify
```

Each arrow is a command to one named participant; each participant answers, and the orchestrator
decides the next step from that answer. No participant listens to another participant.

`POST /api/orders` returns `201 CREATED` as soon as the order is accepted; the rest happens over
Kafka. **[Backend flow](#backend-flow)** below walks the whole path — what commits with what, both
compensation branches, and what happens when a step goes quiet.
[`docs/architecture/saga.md`](docs/architecture/saga.md) has the correctness argument in full, and
[ADR-011](docs/architecture/adr-011-saga-orchestration.md) the design decisions behind it.

---

## Stack

| | |
|---|---|
| Java 21, Spring Boot 3.4.1, Spring Cloud 2024.0.0 | Maven multi-module |
| PostgreSQL 16 — one database per service | Flyway migrations |
| Redis 7 — token deny-list, read caches, rate limits | Kafka (KRaft, no ZooKeeper) |
| Spring Security + JWT access/refresh | Spring Cloud Gateway |
| springdoc OpenAPI | Actuator, Micrometer, Prometheus, Grafana |
| JUnit 5, Mockito, Testcontainers | Docker, Kubernetes, GitHub Actions |

---

## Services

| Service | Port | Database | Role |
|---|---|---|---|
| **API Gateway** | 8080 | — | Single entry point: routing, JWT verification, rate limiting, circuit breaking |
| **Auth** | 8081 | `commerceflow_auth` | Identity; issues the tokens every other service verifies |
| **Order** | 8082 | `commerceflow_order` | The saga orchestrator; CQRS read model |
| **Inventory** | 8083 | `commerceflow_inventory` | Catalogue and stock; reserves and compensates |
| **Payment** | 8084 | `commerceflow_payment` | Charges the customer when commanded, and answers |
| **Notification** | 8085 | `commerceflow_notification` | Terminal participant; tells the customer |

---

## Backend flow

The whole path of one order, end to end. [`docs/architecture/saga.md`](docs/architecture/saga.md)
goes deeper on the correctness argument; this is the map.

### 1. The synchronous half — `POST /api/orders`

Everything here happens inside the customer's HTTP request, and it is the only part that does.

```
Client ──▶ API Gateway ──▶ Order Service ──▶ Inventory Service
                                  │            (POST /api/products/lookup)
                                  ▼
                            PostgreSQL  (one transaction)
```

**API Gateway** verifies the JWT, **strips any `X-User-*` header the client sent**, then injects
its own from the verified claims — the difference between defence in depth and a header a client
can forge. It also stamps a `correlationId` if there isn't one, applies the Redis rate limit, and
routes. Order Service verifies the signature again, so a request that reaches a pod directly is
still rejected.

**Order Service** validates the basket, then makes the one synchronous call in the whole flow:
a batched `POST /api/products/lookup` to Inventory for names, prices and currency. Prices are
**snapshotted onto the order line** — an order is a historical record, and a later catalogue change
must not rewrite what the customer agreed to pay. Because this hop is a read, a catalogue outage
can only reject new orders; it can never leave a saga half-finished.

Then one transaction writes six things:

| Written | Why it is in this transaction |
|---|---|
| `orders` + `order_items` | The aggregate — the system of record |
| `order_read_model` | CQRS projection, so the read side can never lag the write side |
| `saga_instance` | The saga's starting position: step 1, deadline set |
| `saga_step_log` | The first row: command sent, awaiting reply |
| `outbox_event` → `RESERVE_INVENTORY` | The first command. Committed with the order or not at all |
| `outbox_event` → `order.created` | The public domain event |

The response is `201 CREATED` with `status: CREATED`, and it does not wait for the saga.
That is deliberate and it is stated in the API contract: a customer who has been told "confirmed"
before stock is held and money has moved has been told something that might not be true.

A client-supplied `idempotencyKey` (partial unique index per customer) makes a retried create
return the original order rather than placing a second one.

### 2. The asynchronous half — four commanded steps

The orchestrator lives in Order Service. It sends a command to **one named participant**, waits for
the reply, and decides the next step from it. No participant listens to another participant.

```
ORCHESTRATOR ─── ① RESERVE_INVENTORY ──▶ inventory.commands ──▶ Inventory
             ◀── inventory.reserved ───────────────────────────────┘
                 order → INVENTORY_RESERVED

             ─── ② PROCESS_PAYMENT ────▶ payment.commands ────▶ Payment
             ◀── payment.completed ─────────────────────────────────┘
                 order → PAID

             ─── ③ CONFIRM_INVENTORY ──▶ inventory.commands ──▶ Inventory
             ◀── inventory.confirmed ───────────────────────────────┘
                 order → COMPLETED    ──▶ publishes order.completed

             ─── ④ NOTIFICATION_SEND ──▶ notification.send ───▶ Notification
             ◀── notification.sent ─────────────────────────────────┘
                 saga → COMPLETED
```

| # | Command | Participant | What changes in its database | Reply | Order is then |
|---|---|---|---|---|---|
| ① | `RESERVE_INVENTORY` | Inventory | `available -= qty`, `reserved += qty`, reservation `RESERVED` | `inventory.reserved` | `INVENTORY_RESERVED` |
| ② | `PROCESS_PAYMENT` | Payment | Payment row `COMPLETED` with the acquirer's transaction id | `payment.completed` | `PAID` |
| ③ | `CONFIRM_INVENTORY` | Inventory | `reserved -= qty` — the hold becomes a permanent deduction | `inventory.confirmed` | `COMPLETED` |
| ④ | `NOTIFICATION_SEND` | Notification | Notification row `SENT` | `notification.sent` | unchanged; saga closes |

Two things about the ordering are on purpose:

**Inventory is first.** The cheapest failure is the one that happens before any money moves. If
stock cannot be held, there is nothing to undo.

**The order completes at ③, not ②.** Being charged is not the same as being sold. The order sits
at `PAID` until the stock is actually written off; saying "completed" while a step is outstanding
would be a lie the customer can see on their screen.

### 3. What commits with what

This is the core of why the flow survives a crash at any point. Every handler is **one
transaction** spanning four things:

```
┌─ one transaction ────────────────────────────────────────────┐
│  1. idempotency claim   processed_event                      │
│  2. saga position       saga_instance + saga_step_log        │
│  3. business change     orders / inventory / payments / …    │
│  4. the next message    outbox_event                         │
└──────────────────────────────────────────────────────────────┘
```

They commit together or not at all. There is no window in which stock moved but the event was
lost, or an event was published for a transaction that then rolled back. A background relay claims
outbox batches with `FOR UPDATE SKIP LOCKED`, so replicas relay concurrently without ever
publishing the same row twice.

### 4. When a step fails

A failure reply is a **decision**, not an error. The participant records it, answers, and commits.

**Stock unavailable — nothing to compensate**

```
① RESERVE_INVENTORY ──▶ ◀── inventory.failed
   order → CANCELLED  (failedStep: INVENTORY)  ──▶ publishes order.cancelled
④ NOTIFICATION_SEND ──▶ ◀── notification.sent
   saga → COMPENSATED
```

No release is sent, because the orchestrator knows the reserve step never succeeded. Which steps
have to be undone is a fact it holds, not something each participant has to work out from what it
happens to have overheard.

**Payment declined — one step to undo**

```
② PROCESS_PAYMENT ───▶ ◀── payment.failed
   order → CANCELLED  (failedStep: PAYMENT)  ──▶ publishes order.cancelled
③ RELEASE_INVENTORY ─▶ ◀── inventory.released     available += qty
④ NOTIFICATION_SEND ─▶ ◀── notification.sent
   saga → COMPENSATED
```

The order is cancelled the moment the decision is known, not after the cleanup finishes — the
customer's screen should not keep saying "processing" while an internal release runs. Every unit
goes back, because `availableQuantity` and `reservedQuantity` are separate counters and the
reservation records exactly what it took.

### 5. When the customer cancels

The saga above runs forward. Cancellation runs a finished saga backwards, and it is a different
shape: nothing failed, someone changed their mind.

```
POST /api/orders/{id}/cancel
   │
   ├─ order → CANCELLED  (immediately)  ──▶ publishes order.cancelled
   │
   ├─ paid?  ─── yes ──▶ ① REFUND_PAYMENT ─▶ ◀── payment.refunded
   │                                              │
   │             no ─────────────────────────────┤
   │                                              ▼
   └────────────────────────────────▶ ② RELEASE or RESTOCK
                                              ─▶ ◀── inventory.released / .restocked
                                                       │
                                          ③ NOTIFY_CUSTOMER
```

**The order is cancelled first, before any of the undoing.** It will not be fulfilled, and that is
knowable right now. Whether the money is back is the *payment's* status — there is no `REFUNDED`
order status, because the order is `CANCELLED` either way.

**Money before goods.** The refund goes first; a customer chasing their money is a worse outcome
than a unit that reappears a second late.

**Release and restock are different operations**, and picking the wrong one fails silently:

| Order got to | Stock is | Undo with | Getting it wrong |
|---|---|---|---|
| `PAID` or earlier | Held, not deducted | `RELEASE` | Restock invents stock nothing ever took |
| `COMPLETED` | Deducted, counted sold | `RESTOCK` | Release finds no hold; the units vanish |

The choice is made at cancellation time and stored on `saga_instance.stock_undo`, because
cancelling overwrites the order status it was read from.

**Cancelling into a running saga is refused with `409`.** A mid-flight order has a command
outstanding; a second one against the same step makes the step guard drop the pending reply, which
is harmless until the dropped reply was *payment succeeded* and the refund has already gone out.
Sagas settle in seconds, so the honest answer is "try again in a moment". A **parked** saga has
already stopped and may be cancelled by an administrator, who is warned to check the payment first.

A refund that fails does **not** halt the cancellation. The order is cancelled and the customer is
already owed; stopping would strand the stock as well. It logs at `error` and leaves the payment
`COMPLETED`, which is what puts it in front of a person.

### 6. What an order costs

Four numbers and one rule about the order they are worked out in.

```
subtotal      sum(listPrice x quantity)              the goods, before anything came off
discount      sum(discountAmount x quantity)         coupons and promotions
tax           sum(per-line tax)                      each line at its own rate
delivery      from the rate card, or 0               free above a threshold, or collected
--------------------------------------------------------------------------------
total         subtotal - discount + tax + delivery   what the card is charged
```

**The sequence is not arbitrary.** Discounts settle first because they change the taxable base;
delivery is quoted next because the free-delivery threshold is measured against what the customer
actually pays for the goods; tax is applied last, per line. Getting the order wrong does not fail —
it produces an invoice that is out by a small amount, on some baskets and not others.

**Tax is per line, then added up. Never the other way round.** Taxing the order total once is one
multiplication and is wrong the moment a basket mixes rates, which in most of Europe is as soon as
somebody buys a book and a kettle. Each line is taxed at its own rate, each result is rounded to
something the currency can express, and the rounded figures are summed — so a customer adding their
invoice up by hand gets the total printed on it.

**Rates are tables, not constants.** `tax_rates` is keyed by country and category *slug*;
`shipping_rates` by country and method, with a rest-of-world row so an unlisted destination is
priced approximately rather than rejected at the last step of checkout. Both are changed with an
`UPDATE`, because a tax rate is changed by a legislature and a delivery price by whoever runs the
shop.

**Everything is snapshotted onto the order.** The rate, what the tax was called, the delivery
window quoted. When a government moves VAT next April, an old order still explains itself, and no
report has to reconstruct historic rates from a table that has moved on.

### 7. Discount codes

Three kinds — a percentage, a fixed amount, free delivery — and one line of code that matters:

```sql
UPDATE coupons SET redemption_count = redemption_count + 1
 WHERE id = ? AND (max_redemptions IS NULL OR redemption_count < max_redemptions)
```

Everything else about a coupon is checked by **reading**: the validity window, the minimum basket,
the per-customer limit. Any of those can be true when read and false a moment later. Only that
conditional update is atomic, and only its row count is trusted — read-modify-write on a counter
that limits how many times something may happen is how a code meant for the first hundred
customers is honoured a hundred and eleven times with nothing reporting an error.

`POST /api/coupons/preview` deliberately spends nothing, so a basket page can show what a code is
worth without burning a limited campaign on curiosity. The consequence is honest and stated: a code
can pass the preview and be refused at checkout, because the last one went in between.

**A fixed amount is spread across the lines**, because tax is per line and an order-level discount
has no rate. Ten off three equal lines is 3.333… each; rounded independently that is 9.99 and the
customer is short-changed on a coupon that said ten. The shares are floored and the remainder is
handed out one cent at a time to the lines closest to earning it, so the parts sum to the whole by
construction.

**Cancelling gives the code back.** A declined card is not a spent coupon.

### 8. The basket, and why it is the opposite of an order

| | Order | Basket |
|---|---|---|
| What it is | A record of what was agreed | A list of intentions |
| Prices | Frozen at checkout | Read live, every request |
| A repriced product | Shows the price that was paid | Shows today's price |
| Where it lives | Postgres, forever | The browser *and* the server |

An order line copies the name, the picture, the price, the tax rate. A basket line is a product id
and a quantity, and nothing else. A price stored in a basket would mean one saved on Tuesday
quoting Tuesday's price and the checkout charging Friday's, with the customer watching a number
change between two screens.

**Both a browser basket and a server basket exist, and neither is a cache of the other.** The
browser owns *now* — it works signed out, it works offline, and a quantity control does not wait
for a round trip. The server owns *between devices*. Every local change is written through when
there is an account to write it for, and signing in merges the two, taking the **larger** quantity
of each product rather than the sum: two on a phone and two on a laptop is one intention expressed
twice.

**Problems are reported, never silently fixed.** A withdrawn product, an out-of-stock line, a
quantity larger than what is left — each comes back with an `issue` and the line is left alone.
Quietly trimming somebody's basket is the version that feels helpful: they come back to buy five,
are given two, and find out from the invoice.

### 9. Where the stock actually is

A single global stock number is a lie the moment a shop has two buildings. It says twelve are
available; six are in Amsterdam and six in Milan, and an order for eight either ships in two
parcels from two countries or cannot ship at all.

- `stock_levels` (warehouse × product) is **the truth**.
- `inventory_items` keeps its quantity columns as a **summary**, recomputed from those rows in the
  same transaction as every movement — so a product listing can show availability without summing a
  warehouse table per tile. Nothing reserves against it.

Allocation, in order: **one building if one can fill the whole line** (splitting means two parcels
for a customer who ordered one thing), then the **destination country** (one fewer customs form and
usually a day), then **priority**, then the **fullest**. If nothing can cover a line, nothing is
allocated for it — a part-filled line is an order the customer did not place.

Each reservation row records the building its units came from. Releasing, confirming and restocking
act on *that* building rather than working one out again, which would be a guess and would be wrong
exactly when stock has moved since.

### 10. After the money: getting the parcel there

Fulfilment is a **separate lifecycle**, and that is deliberate.

```
order      CREATED -> INVENTORY_RESERVED -> PAID -> COMPLETED
shipment                                            PENDING -> PICKING -> DISPATCHED
                                                    -> IN_TRANSIT -> DELIVERED
```

`OrderStatus` is the saga's state machine. Every transition in it is guarded so that a late or
duplicated reply cannot move an order backwards — that guard is what makes redelivery safe. A
parcel runs on a different clock, is driven by people and carriers, and legitimately *does* go
backwards: a delivery marked at the wrong building, a return. Merging the two would mean either
loosening the guard or refusing corrections warehouse staff genuinely need to make.

So an order is `COMPLETED` while its parcel is `IN_TRANSIT`. Two facts, both true, neither
pretending to be the other.

Every change appends to a history rather than overwriting a column, because "when did this ship?"
and "the carrier says they tried on Tuesday" are the two questions support actually gets. Dispatch,
a failed attempt, delivery and a return email the customer; picking does not — an email per
internal state change teaches people to ignore mail from the shop.

### 11. Checking out without an account

A guest gets a **real account with no password**. The alternative — a nullable `userId` on orders —
means the basket, the address book, the order history, the payment and the saga each grow a second
code path for a customer who might not be a customer, and the first one that forgets is a null
pointer somewhere expensive.

It also gives the customer something: setting a password later through the ordinary reset flow
finds their orders already there.

`POST /api/auth/guest` **refuses an address that already has an account**. It hands out a session
in exchange for an email address and nothing else; returning one for an existing account would let
anybody type your address and be signed in as you.

### 12. When a step does not answer

Every command carries a deadline (`step-timeout`, default 2 minutes). `SagaTimeoutMonitor` sweeps
every 30 seconds:

| Situation | What happens |
|---|---|
| Under the retry budget | Re-send the command, reset the deadline. `commerceflow_saga_step_resent_total` |
| Budget spent (default 3 sends) | Step logged `TIMED_OUT`, saga parked `STALLED`. `commerceflow_saga_stalled` → Prometheus alert |
| Reply lands mid-sweep | The optimistic lock rolls the sweeper back. The reply won; nothing to time out |

A parked saga is **not compensated automatically.** A payment step that has gone quiet may already
have taken the customer's money, and cancelling the order on that guess is a worse failure than the
outage that caused it. The saga is left exactly as it is, fully described by its step log, for an
operator — [`docs/runbook.md`](docs/runbook.md) §1 has the query and the one-line resume.

### 13. The four rules that make redelivery safe

| Rule | Where | Stops |
|---|---|---|
| **Outbox** | Every producer | A state change whose event was never published, or the reverse |
| **Idempotency ledger** | Every consumer, claimed before any work | The same message being processed twice |
| **Step guard** | Orchestrator | A late `payment.failed` cancelling an order that already completed |
| **Reply rule** | Every participant | The saga deadlocking when a command is re-sent |

The last one is the one most easily got wrong. **Every command produces exactly one reply,
including a command for work already done** — a re-sent `RESERVE_INVENTORY` for an already-reserved
order answers again with the original reservation id. The tempting alternative, noticing the
duplicate and returning quietly, deadlocks the saga: the orchestrator re-sent *precisely because*
no answer arrived. Silence is not idempotence.

There is exactly one deliberate exception, in Payment: a payment stuck in `PENDING` has no safe
answer, because the money may or may not have moved. That case stays silent and is escalated to a
human by the timeout above.

### 14. Where it lives

| Concern | File |
|---|---|
| The sequence, on one screen | `orderservice/saga/entity/SagaStep.java` |
| Every decision the saga makes | `orderservice/saga/OrderSagaOrchestrator.java` |
| Live position of one saga | `orderservice/saga/entity/SagaInstance.java` |
| Audit trail: command, reply, retry, latency | `orderservice/saga/entity/SagaStepLog.java` |
| Deadlines and retries | `orderservice/saga/SagaTimeoutMonitor.java` · `SagaRecoveryService.java` |
| The seven reply topics | `orderservice/kafka/SagaReplyListener.java` |
| Participants | `inventoryservice/kafka/InventoryCommandListener.java` · `paymentservice/…/PaymentCommandListener.java` · `notificationservice/…/NotificationCommandListener.java` |
| Message types and their topics | `common/kafka/KafkaTypeMappings.java` |
| The order of operations on money | `orderservice/pricing/OrderPricingService.java` |
| Per-line tax, and why it is per line | `orderservice/pricing/TaxCalculator.java` |
| Spreading a fixed discount without losing a cent | `orderservice/promotion/DiscountAllocator.java` |
| The one atomic coupon claim | `orderservice/promotion/CouponRepository.java` |
| Basket rules: live prices, reported problems | `orderservice/cart/CartService.java` |
| Which building an order ships from | `inventoryservice/service/StockAllocator.java` |
| Two lifecycles, kept apart | `orderservice/fulfilment/ShipmentStatus.java` |
| Why an upload's declared type is ignored | `inventoryservice/service/ImageFormat.java` |
| A guest account, and what it knowingly accepts | `authservice/service/GuestSessionService.java` |

---

## Run it

Requires Docker with Compose v2. Nothing else — the build happens inside the images.

```bash
docker compose up -d --build

# First start pulls images, runs five sets of Flyway migrations and creates the topics.
docker compose ps
docker compose logs -f order-service

# Order history for the UI to show. Takes about half a minute — it places real orders and
# waits for each saga to finish.
./scripts/seed-demo-data.sh
```

| | |
|---|---|
| **Storefront and admin console** | http://localhost:3001 |
| API | http://localhost:8080 |
| Swagger UI (all five services) | http://localhost:8080/swagger-ui.html |
| Kafka UI | http://localhost:8090 |
| Prometheus | http://localhost:9090 |
| Grafana (`admin` / `admin`) | http://localhost:3000 |

### Demo data

The catalogue seeds itself, with a sold-out item, one below its reorder level and one deactivated,
so every list control in the UI has something to act on. It also opens **two warehouses** and
splits the stock between them, so the allocation path that has to choose a building — and the one
that has to split a line across two — are exercised without an operator setting anything up. A
feature that only ever runs in production is a feature nobody has seen work.

Tax rates, delivery rate cards and four discount codes are seeded by Flyway for the same reason:
`WELCOME10`, `FREESHIP`, `TENOFF` (capped, so the "fully redeemed" path is reachable) and
`SUMMER24` (expired, so the "has expired" message is reachable without waiting a year).

That happens in `DemoCatalogueInitializer`, behind a property only `docker-compose.yml` turns on —
a deployed catalogue never grows demo products.

Order history cannot work that way. One order spans five databases, and every row in it is
written by a different service inside its own transaction. Inserting that with SQL would mean
inventing state the code never produced, and the fixtures would start lying the day the saga
changed. So `seed-demo-data.sh` places real orders through the real API and waits for the sagas —
which is also why it takes half a minute rather than being instant.

After it runs:

| Account | Password | |
|---|---|---|
| `ada@commerceflow.io` | `Demo-pass-2026` | 5 orders: three completed, one declined, one out of stock |
| `liam@commerceflow.io` | `Demo-pass-2026` | 3 orders, one of them declined |
| `admin@commerceflow.io` | `ChangeMe-Admin-2026` | The admin console |

Run it again for another round of orders. Start over with
`docker compose down -v && docker compose up -d --build`.

Building locally instead needs JDK 21 and Maven 3.9+:

```bash
mvn clean verify                 # compile + unit tests (no Docker needed)
mvn verify -Pintegration         # + Testcontainers integration tests (needs Docker)
```

---

## Walk the saga end to end

`./scripts/seed-demo-data.sh` does all of this and leaves the result in the UI. What follows is
the same thing by hand, for when you want to watch one order move.

The seeded catalogue makes both branches reachable without any setup.

```bash
API=http://localhost:8080

# 1. Register and log in.
curl -sX POST $API/api/auth/register -H 'Content-Type: application/json' -d '{
  "email":"ada@commerceflow.io","password":"Demo-pass-2026","fullName":"Ada Lovelace"}'

TOKEN=$(curl -sX POST $API/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"ada@commerceflow.io","password":"Demo-pass-2026"}' \
  | python -c 'import json,sys;print(json.load(sys.stdin)["data"]["accessToken"])')

# 2. Browse the catalogue (no token needed).
curl -s "$API/api/products?size=3" | python -m json.tool

# 3. HAPPY PATH — a laptop at 1899.00 is below the decline threshold.
ORDER=$(curl -sX POST $API/api/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{
    "items":[{"productId":"11111111-1111-1111-1111-111111111101","quantity":1}],
    "shippingAddress":"Keizersgracht 1, 1015 CJ Amsterdam, NL"}' \
  | python -c 'import json,sys;print(json.load(sys.stdin)["data"]["id"])')

sleep 3
curl -s $API/api/orders/$ORDER -H "Authorization: Bearer $TOKEN" | python -m json.tool
# → "status": "COMPLETED"

# 4. COMPENSATION, payment branch — 6 laptops is 11394.00, at or above the 10000.00
#    threshold of the simulated acquirer, so the charge is declined.
FAILED=$(curl -sX POST $API/api/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{
    "items":[{"productId":"11111111-1111-1111-1111-111111111101","quantity":6}],
    "shippingAddress":"Keizersgracht 1, 1015 CJ Amsterdam, NL"}' \
  | python -c 'import json,sys;print(json.load(sys.stdin)["data"]["id"])')

sleep 4
curl -s $API/api/orders/$FAILED -H "Authorization: Bearer $TOKEN" | python -m json.tool
# → "status": "CANCELLED", "failedStep": "PAYMENT", "failureReason": "INSUFFICIENT_FUNDS"
# The six units are back in stock — check availableQuantity on the product.

# 5. COMPENSATION, stock branch — this SKU is seeded with exactly 2 units.
curl -sX POST $API/api/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{
    "items":[{"productId":"11111111-1111-1111-1111-111111111107","quantity":50}],
    "shippingAddress":"Keizersgracht 1, 1015 CJ Amsterdam, NL"}'
# → after a moment: "status": "CANCELLED", "failedStep": "INVENTORY"

# 6. What the customer was told.
curl -s $API/api/notifications -H "Authorization: Bearer $TOKEN" | python -m json.tool
```

---

## Layout

```
commerceflow/
├── common/commerceflow-common/   Event contracts, JWT, outbox, idempotency, error envelope
├── gateway/spring-cloud-gateway/ API Gateway
├── services/                     auth · inventory · order · payment · notification
├── infrastructure/               postgres · kafka · redis · prometheus · grafana
├── k8s/                          Kustomize manifests (see k8s/README.md)
├── docs/                         OpenAPI contract, saga design, runbook, retention policy
├── scripts/                      Operational helpers: seed, smoke test, backup, restore
├── load-test/                    k6 scenario: checkout under contention
├── .github/workflows/            ci.yml · cd.yml · pr-validation.yml
└── docker-compose.yml
```

Inside a service, the package layout follows
[`coding-standard.md`](.github/docs/coding-standard.md): `controller · service · repository ·
entity · dto · mapper · config · kafka`. No business logic in a controller.

---

## The parts worth knowing about

The saga itself is covered in [Backend flow](#backend-flow) — outbox, idempotency ledger, step
guard, reply rule, timeouts. What follows is everything else.

**CQRS on the order query side.** `orders`/`order_items` is the write model; `order_read_model` is
a flat row with the items denormalised into JSON. The projection is written in the *same*
transaction as the state change, so the read side is strongly consistent while still being a
single-row, index-only read — and it can be rebuilt from the aggregate at any time.

**JWT verified twice.** The gateway verifies the token and injects `X-User-*` headers — after
stripping any the client sent, which is the difference between defence in depth and a header a
client can forge. Each service verifies the signature again, so a request that reaches a pod
directly is still rejected.

**Two stock counters.** `availableQuantity` and `reservedQuantity` are separate, which is what
makes `inventory.released` exact rather than approximate. A back-office stock correction never
touches units a running saga is holding.

**One query answers "where is this order".** `saga_instance` holds the live position and
`saga_step_log` every command, reply, retry and latency. That question used to mean grepping a
correlation id across four services' logs and hoping none had rolled over.

---

## API

The reviewed contract is [`docs/api/commerceflow-openapi.yaml`](docs/api/commerceflow-openapi.yaml).
Each service also serves its generated spec at `/v3/api-docs`; `scripts/export-openapi.sh` pulls
them down so drift shows up in a diff.

| | |
|---|---|
| `POST /api/auth/register` `POST /api/auth/login` | Also `refresh`, `logout`, `me` |
| `GET /api/products` `GET /api/products/{id}` `PUT /api/products/{id}/stock` | Plus SKU lookup and batch lookup |
| `POST /api/orders` `GET /api/orders/{id}` `GET /api/orders` | |
| `POST /api/payments/process` | Plus payment lookup by id and by order |
| `GET /api/notifications` | |
| `POST /api/reservations/{orderId}/release` | Operator lever for stuck stock — see the runbook before using it |
| `GET /api/audit/{service}` | The operator audit trail. One path per service, because each keeps its own log — ADMIN, read only |
| `POST /api/orders/{id}/returns` `POST /api/returns/{id}/refund` | Returns: the customer raises, an operator approves, receives and refunds |

---

## Testing

| Command | Runs | Needs Docker |
|---|---|---|
| `mvn test` | Unit tests only | No |
| `mvn verify` | Unit tests, packaging | No |
| `mvn verify -Pintegration` | Testcontainers integration tests as well | Yes |

`mvn clean verify` is green: **430 unit tests, 0 failures, 0 warnings**, all six executable jars
produced. The integration suites need a Docker daemon and run in CI.

Integration tests are tagged `integration` and run under Failsafe, so a developer without a Docker
daemon still gets a green `mvn test`. They start real PostgreSQL, Kafka and Redis containers and
exercise the orchestrator and each participant end to end — the orchestrator test plays every
participant in turn, answering each command and asserting on the one that follows. It covers both
compensation branches, redelivery, the step guard that stops a late reply cancelling a paid order,
and the guarantee that a customer is never charged twice.

---

## Deployment

GitHub Actions builds, tests, scans, and pushes six images; a separate workflow deploys **the tag
CI already built** — it never builds, so what reaches production is byte-for-byte what was tested.
Staging deploys automatically from `main`; production needs a tag and an environment approval.

Kubernetes manifests and the operational notes that go with them are in
[`k8s/README.md`](k8s/README.md).

---

## Documentation

| | |
|---|---|
| [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) | What was built, in what order, and the decisions taken along the way |
| [`docs/architecture/saga.md`](docs/architecture/saga.md) | The saga: design, state machine, failure matrix |
| [`docs/architecture/adr-011-saga-orchestration.md`](docs/architecture/adr-011-saga-orchestration.md) | How the saga is orchestrated, what it costs, and the history behind the design |
| [`docs/architecture/snapshots.md`](docs/architecture/snapshots.md) | Why an order never reads the catalogue, and exactly what it copies |
| [`docs/architecture/payments.md`](docs/architecture/payments.md) | What changes when the acquirer is real, and where the customer pays |
| [`docs/runbook.md`](docs/runbook.md) | Diagnosing a parked saga, a dead-letter topic, a stalled outbox, a failed refund, a stuck parcel |
| [`docs/data-retention.md`](docs/data-retention.md) | What is kept, for how long, and what is deliberately never deleted |
| [`load-test/README.md`](load-test/README.md) | Checkout under contention: does the stock lock hold, and what does it cost |
| [`docs/api/commerceflow-openapi.yaml`](docs/api/commerceflow-openapi.yaml) | The API contract |
| [`k8s/README.md`](k8s/README.md) | Deploying and operating on Kubernetes |
| [`.github/docs/event-flow.md`](.github/docs/event-flow.md) · [`contracts/events.md`](.github/docs/contracts/events.md) | The message contract: topics, commands, replies, envelope |
| [`.github/docs/`](.github/docs/) | The architecture documents this was built to |

---

## Security note

`docker-compose.yml` and `k8s/01-config.yaml` ship placeholder credentials so the stack runs
immediately. **Every deployed environment must override `JWT_SECRET` and the database password**
with real secrets — see [`k8s/README.md`](k8s/README.md) for how, and note that the shared JWT
signing key means rotating it invalidates all live sessions by design.
