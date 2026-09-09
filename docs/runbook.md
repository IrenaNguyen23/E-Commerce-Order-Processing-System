# Runbook

Diagnosing CommerceFlow when something is wrong. Ordered by what an on-call engineer is most
likely to be paged about.

Commands assume Kubernetes; the Docker Compose equivalents are at the bottom.

---

## First move, always: get the correlation id

Every log line, every API response and every Kafka record carries `correlationId`. It is stamped
by the gateway on the way in and propagated over HTTP headers, Kafka record headers and the
outbox. One id is one customer action across all six services.

```bash
# From a support ticket: the customer has the id from the API response envelope.
kubectl -n commerceflow logs -l app.kubernetes.io/part-of=commerceflow --tail=-1 \
  | grep '"correlationId":"<id>"'

# From an order id, when the customer does not have the correlation id.
kubectl -n commerceflow exec -it postgres-0 -- psql -U commerceflow -d commerceflow_order -c \
  "SELECT correlation_id, event_type, status, created_at FROM outbox_event
    WHERE aggregate_id = '<order-id>' ORDER BY created_at;"
```

---

## 1. Alert: `StalledOrderSagas`

A saga stopped getting replies and exhausted its retry budget. The gauge is
`commerceflow_saga_stalled`.

**What it means:** the orchestrator sent a command three times and never got an answer. The order
is not lost — it is parked, with its exact position recorded, waiting for a decision.

```bash
# Which sagas are parked, and on which step?
kubectl -n commerceflow exec -it postgres-0 -- psql -U commerceflow -d commerceflow_order -c \
  "SELECT order_number, current_step, attempt, updated_at, now() - updated_at AS parked_for
     FROM saga_instance
    WHERE state = 'STALLED'
    ORDER BY updated_at;"
```

`current_step` names the participant to check:

| Parked on | Waiting on | Check |
|---|---|---|
| `RESERVE_INVENTORY` | Inventory | `kubectl -n commerceflow get pods -l app.kubernetes.io/name=inventory-service` |
| `PROCESS_PAYMENT` | Payment | same for `payment-service` |
| `CONFIRM_INVENTORY` / `RELEASE_INVENTORY` | Inventory | as above |
| `NOTIFY_CUSTOMER` | Notification | same for `notification-service` |

Then the full history of one saga, which is usually enough on its own:

```sql
SELECT step, compensation, attempt, status, detail,
       created_at, completed_at, completed_at - created_at AS latency
  FROM saga_step_log
 WHERE saga_id = '<order-id>'
 ORDER BY created_at;
```

Work through sections 2 and 3 to find out *why* that participant is not answering. Once it is back,
clearing the stall is one update — the sweeper picks the saga up again on its next pass:

```sql
-- Only after the participant is confirmed healthy.
UPDATE saga_instance
   SET state = CASE WHEN failed_step IS NULL THEN 'STARTED' ELSE 'COMPENSATING' END,
       attempt = 0,
       step_deadline = now()
 WHERE id = '<order-id>' AND state = 'STALLED';
```

**Do not** cancel a parked order by hand while the cause is unknown. A saga parked on
`PROCESS_PAYMENT` may already have been charged — check the acquirer and `payment.payments` for
that `order_id` first. This is exactly why the orchestrator parks rather than compensating on a
guess.

---

## 1b. Alert: `StaleInventoryReservations`

Stock has been held for longer than any order should take. The gauge is
`commerceflow_inventory_reservations_stale`.

**What it means:** a hold exists that no saga is going to finish. Those units are invisible to
every customer until someone acts, so the shop is quietly smaller than it thinks.

Most stuck sagas never get here — the orchestrator abandons an unanswered reserve by itself and
keeps retrying an unanswered release. What reaches this alert is the residue: a saga parked at the
payment step, or a hold whose saga no longer exists at all.

```bash
# Which holds, and how old?
kubectl -n commerceflow exec -it postgres-0 -- psql -U commerceflow -d commerceflow_inventory -c \
  "SELECT r.order_id, r.order_number, r.created_at, now() - r.created_at AS held_for,
          sum(ri.quantity) AS units
     FROM inventory_reservations r JOIN reservation_items ri ON ri.reservation_id = r.id
    WHERE r.status = 'RESERVED' AND r.created_at < now() - interval '2 hours'
    GROUP BY r.order_id, r.order_number, r.created_at
    ORDER BY r.created_at;"
```

**Then check the saga before doing anything.** This is the whole reason the sweep reports rather
than releases — from inside Inventory the two cases look identical and want opposite treatment:

```sql
-- commerceflow_order
SELECT state, current_step, attempt, failed_step, failure_reason, updated_at
  FROM saga_instance WHERE id = '<order-id>';
```

| What you find | What it means | What to do |
|---|---|---|
| No row at all | The saga is gone — usually a deploy that cut over mid-flight. Nothing will ever finish it. | Safe to release. |
| `STALLED` at `PROCESS_PAYMENT` | **The customer may have been charged.** | Check `payments` for that `order_id` and reconcile with the acquirer *first*. Release only once you know no charge stands. |
| `STALLED` at `CONFIRM_INVENTORY` | The order was paid for. The stock is sold and holding it is correct. | Do **not** release. Fix why the confirm is not being answered, then resume the saga (§1). |
| `STARTED` or `COMPENSATING` | Still running. | Leave it; go back to §1. |

Once you are sure, release it:

```bash
curl -X POST "$API/api/reservations/<order-id>/release" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"reason":"Saga orphaned by the 2026-08-27 deploy; no payment found"}'
```

That returns the units to `available_quantity`, closes the reservation, and publishes
`inventory.released` — so the release is audited exactly like a commanded one. Do it through the
endpoint rather than by hand in SQL: the counters, the reservation status and the event have to
move together, and a manual `UPDATE` gets two of the three.

---

## 2. Events are not being published — the outbox is backing up

```sql
-- Run against each service database in turn.
SELECT status, count(*), min(created_at) AS oldest
  FROM outbox_event GROUP BY status;
```

| Symptom | Cause | Action |
|---|---|---|
| Growing `PENDING`, `attempts = 0` | The relay is not running | Is the pod up? Is `commerceflow.outbox.enabled` still true? |
| Growing `PENDING`, `attempts` climbing | Kafka is unreachable or rejecting | Check the broker; check `last_error` |
| Rows in `FAILED` | Exhausted `maxAttempts` | Investigate `last_error`, then requeue (below) |

```sql
-- What is it actually failing on?
SELECT id, event_type, topic, attempts, last_error
  FROM outbox_event WHERE status = 'FAILED' ORDER BY created_at LIMIT 20;

-- Requeue after fixing the cause. Safe: consumers deduplicate on event_id.
UPDATE outbox_event SET status = 'PENDING', attempts = 0, last_error = NULL
 WHERE status = 'FAILED' AND id = '<row-id>';
```

---

## 3. Events are published but not consumed — consumer lag

```bash
kubectl -n commerceflow exec -it kafka-0 -- \
  kafka-consumer-groups --bootstrap-server localhost:9092 --describe --all-groups
```

Lag that grows steadily and never drains means the consumer is down, crash-looping, or stuck on a
message it cannot process.

```bash
kubectl -n commerceflow logs -l app.kubernetes.io/name=inventory-service --tail=200
kubectl -n commerceflow describe pod -l app.kubernetes.io/name=inventory-service
```

A consumer that is up but making no progress is usually blocked on one poison record at the head
of a partition — see section 4.

---

## 4. Messages on a dead-letter topic

Every topic has a `.DLT` counterpart. A record lands there after the retry budget is exhausted, or
immediately if the failure is a domain rule violation or a malformed payload — replaying those
cannot change the outcome, and retrying forever would block the partition and every other order
that hashes to it.

```bash
kubectl -n commerceflow exec -it kafka-0 -- \
  kafka-console-consumer --bootstrap-server localhost:9092 \
    --topic inventory.commands.DLT --from-beginning --max-messages 20 \
    --property print.headers=true
```

The `kafka_dlt-exception-message` header says why. Then:

- **A bug in the consumer** — fix, deploy, and replay the DLT back onto the source topic.
- **A genuinely invalid message** — the order it belongs to is stuck. Decide with the business
  whether to cancel it; the customer has not been charged unless it got past Payment.

---

## 5. A customer says they were charged twice

They were not, and this is quick to prove:

```sql
-- commerceflow_payment. The unique index on order_id makes a second charge impossible.
SELECT id, order_id, amount, status, transaction_id, created_at
  FROM payments WHERE order_id = '<order-id>';
```

One row, always. If the acquirer statement shows two charges for one `transaction_id`, the
duplicate is on the acquirer side, not here.

What *can* happen: `GATEWAY_ERROR`. The acquirer call timed out, so the platform recorded a
failure and compensated — but the charge may in fact have gone through. Those need manual
reconciliation:

```sql
SELECT id, order_id, amount, created_at FROM payments
 WHERE status = 'FAILED' AND failure_reason = 'GATEWAY_ERROR'
 ORDER BY created_at DESC;
```

---

## 6. Stock looks wrong

```sql
-- commerceflow_inventory
SELECT p.sku, i.available_quantity, i.reserved_quantity
  FROM inventory_items i JOIN products p ON p.id = i.product_id
 WHERE p.sku = '<sku>';

-- Reserved units that no longer belong to a live saga: the reconciliation query.
SELECT r.order_id, r.status, r.created_at, sum(ri.quantity) AS units
  FROM inventory_reservations r JOIN reservation_items ri ON ri.reservation_id = r.id
 WHERE r.status = 'RESERVED' AND r.created_at < now() - interval '1 hour'
 GROUP BY r.order_id, r.status, r.created_at;
```

A `RESERVED` reservation older than an hour means its saga stopped moving — go back to section 1
and read that saga's step log, which will say which command is outstanding. Do not release it by
hand until the order is known to be terminal, or you will free stock for an order that is about to
be paid.

Remember that a back-office stock correction (`PUT /api/products/{id}/stock`) only moves
`availableQuantity`. `reservedQuantity` belongs to running sagas.

---

## 7. Everything returns 401

Almost always the JWT signing key.

```bash
# Every service must verify with the key Auth Service signs with.
for service in auth order inventory payment notification api; do
  kubectl -n commerceflow get deploy ${service}-service -o jsonpath='{.spec.template.spec.containers[0].envFrom}' 2>/dev/null
done
kubectl -n commerceflow get secret commerceflow-secrets -o jsonpath='{.data.JWT_SECRET}' | base64 -d | wc -c
```

- Fewer than 32 bytes → services will not start at all (HS256 requires it, and the check is at
  construction time).
- Recently rotated → **all live sessions are invalid by design**. Clients must log in again.
- One service disagreeing with the others → that service was deployed with a stale secret. Restart
  it after confirming the secret is correct.

---

## 8. Everything returns 503 from the gateway

A circuit breaker is open, or the service behind it is down.

```bash
kubectl -n commerceflow exec deploy/api-gateway -- \
  curl -s localhost:8080/actuator/health | python -m json.tool
kubectl -n commerceflow get pods
```

The breaker closes on its own 15 seconds after the downstream recovers. If the gateway is healthy
and the downstream is healthy but requests still fail, check the `NetworkPolicy` — on a cluster
whose CNI does not enforce them the objects apply and do nothing, which hides the problem until it
is moved to one that does.

---

## 9. Everything returns 429

Rate limiting is Redis-backed. If Redis is unreachable, the limiter denies rather than allows.

```bash
kubectl -n commerceflow exec -it redis-0 -- redis-cli ping
kubectl -n commerceflow logs -l app.kubernetes.io/name=api-gateway --tail=100 | grep -i redis
```

Auth routes are limited per IP (10 rps, burst 20); everything else per authenticated user.

---

## 10. The read model disagrees with the order

It should not be able to: the projection is written in the same transaction as the state change.
If it ever does, the write model is the source of truth and the projection can be rebuilt.

```sql
-- Find the disagreement.
SELECT o.id, o.status AS write_model, v.status AS read_model
  FROM orders o JOIN order_read_model v ON v.order_id = o.id
 WHERE o.status <> v.status;
```

`OrderProjectionService.rebuild(orderId)` regenerates one projection from the aggregate.

---

## 11. A refund has not reached the customer

A cancelled or compensated order should leave a `REFUNDED` payment behind. When a customer says
the money has not come back, find out which of three things happened, because they need different
answers.

```sql
-- commerceflow_payment
SELECT id, order_number, status, amount, currency, transaction_id, failure_reason,
       created_at, processed_at
  FROM payments
 WHERE order_number = '<order-number>'
 ORDER BY created_at;
```

| What you see | What it means | What to do |
|---|---|---|
| `REFUNDED`, `processed_at` set | The platform refunded it. | Bank timing — card refunds take days to appear on a statement. Give the customer `transaction_id`. |
| `COMPLETED`, order `CANCELLED` | The refund command never ran or never landed. | Section 1: read the saga step log for the compensation step. |
| `FAILED` with a `failure_reason` | The provider refused it. | Read the reason. An expired card usually needs a manual refund at the provider. |
| No row at all | Nothing was ever charged. | Nothing to refund. Say so plainly; the customer is probably looking at an authorisation hold, which drops off on its own. |

Reconcile against the provider before refunding by hand:

```bash
# The platform's view, with the acquirer reference to search for at Stripe.
kubectl -n commerceflow exec -it postgres-0 -- psql -U commerceflow -d commerceflow_payment -c \
  "SELECT order_number, status, amount, transaction_id FROM payments
    WHERE order_number = '<order-number>';"
```

**A manual refund at the provider does not update this database.** Nothing reconciles the two
automatically, so the payment row will still say `COMPLETED` and any later automated refund would
send the money twice. If you refund by hand, record it — audit entry and a note on the ticket —
and treat the discrepancy as something to fix in code, not to repeat.

---

## 12. A parcel is stuck

`shipments.status` is deliberately **not** `orders.status`. An order is `COMPLETED` the moment it
is paid and reserved; the parcel then has its own life, and a shipment that never moves does not
show up as a stalled saga.

```sql
-- commerceflow_order: parcels that have not moved
SELECT s.order_number, s.status, s.carrier, s.tracking_number,
       s.promised_by, s.created_at, s.updated_at
  FROM shipments s
 WHERE s.status IN ('PENDING', 'PICKING', 'DISPATCHED', 'IN_TRANSIT')
   AND s.updated_at < now() - interval '48 hours'
 ORDER BY s.promised_by NULLS LAST;

-- What has happened to one of them, in order.
SELECT status, note, location, recorded_by, recorded_at
  FROM shipment_events
 WHERE shipment_id = '<shipment-id>'
 ORDER BY recorded_at;
```

Read `promised_by` before deciding whether it is late: it comes from the delivery window quoted at
checkout, so a parcel is late against what the customer was actually told, not a target invented
afterwards.

- **`PENDING` for days** — the warehouse never picked it. An operational problem, not a software
  one. Check the warehouse is still `active`; a building left switched off after a stock take
  takes no new work but keeps whatever it had already promised.
- **`DISPATCHED` with no carrier events** — the carrier has it and is not scanning. Chase the
  carrier with `tracking_number`.
- **`ATTEMPTED` repeatedly** — nobody is home. Support contacts the customer; there is nothing to
  fix here.

Progress a shipment through the API (`POST /api/shipments/{id}/events`), never with an `UPDATE`.
The endpoint enforces the lifecycle and writes the event row; a hand-written status change leaves
a parcel whose history does not explain its state.

---

## 13. A discount code has been redeemed more times than it should be

First, check whether it actually has:

```sql
-- commerceflow_order
SELECT code, type, value, max_redemptions, redemption_count, per_customer_limit,
       valid_from, valid_until, active
  FROM coupons
 WHERE code = '<CODE>';

-- What the redemption rows say, which is the figure to trust.
SELECT count(*) AS redemptions, count(DISTINCT user_id) AS customers
  FROM coupon_redemptions
 WHERE code = '<CODE>';
```

`redemption_count` is incremented **only** by the conditional `UPDATE` in
`CouponRepository#claim`, and a database constraint refuses to let it exceed `max_redemptions`.
So if the counter is at its cap and customers are being turned away, the campaign is simply over —
that is the cap working.

If `count(*)` from `coupon_redemptions` is **higher** than `redemption_count`, that is a real bug:
something claimed the discount without going through the conditional update. Escalate.

Abuse looks different from a bug. One customer, many accounts:

```sql
SELECT user_id, count(*) AS uses, min(redeemed_at), max(redeemed_at)
  FROM coupon_redemptions
 WHERE code = '<CODE>'
 GROUP BY user_id HAVING count(*) > 1
 ORDER BY uses DESC;
```

`per_customer_limit` stops one account using a code repeatedly. It cannot stop one person opening
twenty accounts, and nothing in the platform currently does — deliberately, because every
mechanism that would (device fingerprinting, address matching) misfires on households and shared
offices. The lever that works is the cap: turn the code off.

The fastest way is the back office: **Coupons -> the code -> switch it off**. Over the API it is
`PUT /api/coupons/{id}`, and the body is the whole coupon rather than a patch -- read it back
first and resend it with `active` flipped, or you get a 400 about a missing `code` and `type`:

```bash
curl -s "$API/api/coupons/<id>" -H "Authorization: Bearer $ADMIN" \
  | jq '.data | {code, type, value, currency, minimumBasket, maxRedemptions,
                 perCustomerLimit, validFrom, validUntil, active: false}' \
  | curl -X PUT "$API/api/coupons/<id>" -H "Authorization: Bearer $ADMIN" \
         -H 'Content-Type: application/json' -d @-
```

It takes effect on the next order; orders already placed keep the discount they were given.
Deactivating is reversible and audited.

Do not try to delete it instead. `DELETE /api/coupons/{id}` refuses with 409 once a code has been
redeemed, on purpose: those rows are the record of what the campaign cost, and a redemption
pointing at nothing makes the discount on an old invoice unexplainable.

---

## 14. Somebody cannot sign in, and the password is right

After five failed attempts in thirty minutes an account is locked for fifteen. The lock lives in
Redis, keyed by lower-cased email address.

```bash
docker compose exec redis redis-cli GET  "login:locked:<email>"
docker compose exec redis redis-cli TTL  "login:locked:<email>"
docker compose exec redis redis-cli GET  "login:failures:<email>"
```

A `TTL` in seconds is how long they have left. Clear it through the API rather than Redis, so the
unlock is audited and attributable:

```bash
curl -X POST "$API/api/users/<user-id>/unlock-signin" -H "Authorization: Bearer $ADMIN"
```

Two things worth knowing before you go looking for a bug:

- **The throttle fails open.** If Redis is down, sign-in is allowed rather than blocked — an
  outage in a cache must not lock every customer out of the shop. So "nobody can sign in" is never
  the throttle; look at section 7.
- **A failed attempt against an unknown address is counted too.** Otherwise the timing difference
  between "no such account" and "wrong password" tells an attacker which addresses are registered.
  A lock on an address with no account is therefore normal and harmless.

If the address ends in `@erased.invalid`, the account was erased. It cannot be signed into, by
design, and there is no undo — the customer registers again (section 15).

---

## 15. An erasure request did not take everywhere

`DELETE /api/users/{id}` anonymises the account and announces `user.erased`. Each service scrubs
its own copy when the event reaches it, so a service that was down catches up when it returns —
but nothing alerts if it never does.

```sql
-- commerceflow_auth: did the account itself go?
SELECT id, email, full_name, enabled FROM users WHERE id = '<user-id>';
--   erased: email ends @erased.invalid, full_name 'Deleted account', enabled false

-- commerceflow_order: the orders and the read model
SELECT order_number, user_email, recipient_name, shipping_country, shipping_postal_code
  FROM orders WHERE user_id = '<user-id>';
--   erased: placeholder email and name; country KEPT (it chose the tax rate), postcode NULL

-- commerceflow_inventory: the reviews
SELECT id, author_name, rating, status FROM product_reviews WHERE user_id = '<user-id>';
--   erased: author_name is the placeholder, the review text and rating stay
```

Anything still showing real data means the event has not been consumed there. Check that
service's consumer lag (section 3) and its dead-letter topic (section 4) — the erasure is sitting
in one of the two.

To replay it, republish from the auth outbox rather than editing rows by hand: the listeners are
idempotent, and `processed_event` will not let a service scrub twice.

```sql
-- commerceflow_auth: find the event, then reset it for the relay to pick up again.
SELECT id, event_type, status, created_at FROM outbox_event
 WHERE aggregate_id = '<user-id>' AND event_type = 'USER_ERASED';
```

Erasure has a legal deadline measured in weeks, so a few hours of catch-up is not an incident. A
service that has not caught up by the next day is.

---

## 16. Reading the admin audit log

Every administrative action writes a row: role changes, stock corrections, coupon edits, manual
reservation releases, review moderation, cancellations, refunds, erasures. The table is
`admin_audit_log` and it is **local to each service** — the same reason the outbox is.

**Start with the back office: Admin → Audit trail.** It asks all five services and merges the
answers newest first, with filters for who, what action, what it happened to, correlation id and a
date range. If a service cannot be reached it says so at the top of the list rather than quietly
showing a shorter trail — a missing service and a quiet service look identical otherwise.

Over the API, one path per service because there is no single log to read:

```bash
# Everything one administrator did in Order Service.
curl -s "$API/api/audit/orders?actor=ops@commerceflow.io&size=100" \
     -H "Authorization: Bearer $ADMIN" | jq '.data.entries[]'

# One customer action, wherever it was recorded. Five calls, on purpose.
for svc in auth orders inventory payments notifications; do
  curl -s "$API/api/audit/$svc?correlationId=<id>" -H "Authorization: Bearer $ADMIN" \
    | jq --arg s "$svc" '.data.entries[] | {service: $s, occurredAt, actorEmail, action}'
done
```

Administrator only, and read only — there is no endpoint that writes, amends or deletes an entry.
Paging is by position (`nextBeforeAt` / `nextBeforeId` from the response, sent back as `beforeAt` /
`beforeId`), not by page number: page 3 of one service does not cover the same span of time as
page 3 of another, so offsets could not be merged into one ordered list.

SQL is still the right tool for aggregate questions the API does not answer:

```sql
-- Everything one administrator did, in one service.
SELECT occurred_at, action, target_type, target_id, summary, source_ip
  FROM admin_audit_log
 WHERE actor_email = '<email>'
 ORDER BY occurred_at DESC
 LIMIT 100;

-- Everything that happened to one thing.
SELECT occurred_at, actor_email, action, summary
  FROM admin_audit_log
 WHERE target_type = 'ORDER' AND target_id = '<order-id>'
 ORDER BY occurred_at;

-- One customer action across services: same correlation id in each database.
SELECT occurred_at, actor_email, action, target_type, summary
  FROM admin_audit_log
 WHERE correlation_id = '<correlation-id>';

-- Who is unusually busy this week, which is what an abuse investigation starts from.
SELECT actor_email, action, count(*)
  FROM admin_audit_log
 WHERE occurred_at > now() - interval '7 days'
 GROUP BY actor_email, action
 ORDER BY count DESC;
```

`actor_id` is null for actions the system took on its own — an erasure arriving over Kafka, a
sweeper releasing a reservation. That is not a missing value; it is the answer to "who did this",
and the answer is nobody.

The log records **what** changed, not the before and after. Keeping the old and new values would
mean keeping personal data in a table with a two-year retention, which is the opposite of what a
retention policy is for. To find out what a value used to be, use the backups.

---

## 17. A retention sweeper has stopped running

Eight jobs delete old rows on a schedule. When one stops, nothing breaks — a table just grows until
it is a problem, which is the kind of failure that is discovered six months late.

```sql
-- Anything much older than the retention below means its sweeper is not running.
SELECT 'outbox (order)' AS table, min(created_at)   FROM outbox_event WHERE status = 'PUBLISHED'
UNION ALL SELECT 'saga history', min(created_at)    FROM saga_instance
          WHERE state IN ('COMPLETED', 'COMPENSATED')
UNION ALL SELECT 'audit',        min(occurred_at)   FROM admin_audit_log
UNION ALL SELECT 'carts',        min(updated_at)    FROM carts;
```

| Job | Table | Keeps | Runs |
|---|---|---|---|
| Outbox sweeper | `outbox_event` (`PUBLISHED` only) | 7 days | hourly |
| Idempotency sweeper | `processed_event` | 7 days | hourly |
| Verification token sweeper | `verification_tokens` (expired only) | 7 days | hourly |
| Refresh token sweeper | `refresh_tokens` (expired only) | 7 days | hourly |
| Audit sweeper | `admin_audit_log` | 730 days | 03:30 daily |
| Cart sweeper | `carts` | 120 days | 04:00 daily |
| Saga history sweeper | `saga_instance`, `saga_step_log` | 90 days | 04:15 daily |
| Notification sweeper | `notifications` (`SENT` only) | 365 days | 04:45 daily |

Details and the reasoning are in [`docs/data-retention.md`](data-retention.md). Two rules are
worth remembering at three in the morning:

- **`STALLED` sagas are never swept, at any age.** A parked saga is an unfinished piece of
  business and deleting it destroys the only record of what went wrong.
- **`FAILED` and `PENDING` notifications are never swept.** They are the evidence of every message
  the platform failed to deliver.

If a job is not running, the usual cause is that `@EnableScheduling` is missing from that
service's application class, or the service has been scaled to zero. All eight are single-instance
safe but not leader-elected: **with more than one replica, every replica runs every job.** They
are idempotent deletes, so the result is correct and the load is wasted — worth fixing before
scaling out, not after.

---

## 18. Restoring from backup

```bash
./scripts/backup.sh                                    # dump all five databases
./scripts/restore.sh ./backups/<stamp> --into drill    # prove it works: touches nothing live
./scripts/restore.sh ./backups/<stamp> --force         # recovery: DESTROYS current data
```

Stop the services before a real restore. A running service reconnects mid-restore and writes into
a half-restored database.

Redis and Kafka are not backed up, on purpose. Redis holds only things that rebuild or expire, and
a restored token blacklist would be worse than an empty one — it would let revoked tokens back in.
Every Kafka message is derived from an outbox row in Postgres, and the relay republishes anything
unacknowledged.

After a restore, in this order:

1. **Check the schema version.** Flyway reports it at startup, and it must match the code you are
   running. A dump older than the last migration restores cleanly and then fails at the first
   query.
2. **Re-revoke anything that was revoked.** Redis is empty, so tokens revoked before the failure
   are valid again until they expire.
3. **Expect duplicate events.** The relay republishes what was unacknowledged at the moment of the
   dump; `processed_event` dedupes on `(consumer group, event id)`, so consumers absorb them.
4. **Reconcile payments against Stripe.** Anything captured after the dump exists at the provider
   and not here. That gap is money, and it is the one thing no restore brings back.

Run the drill on a schedule, not when you need it. A backup nobody has restored is a file.

---

## 19. A return refund did not go through

A return sits in `RECEIVED` with a `refund_failure` on it. The goods are back, the customer is
owed money, and nobody has sent it. This is the one failure in the system where the shop is in
debt while everything looks calm.

The back office shows it: **Admin -> Returns -> Goods received** lists them with the provider's
reason in red and a retry button. What follows is for when the button is not enough.

```sql
-- commerceflow_order: returns that are owed money
SELECT r.order_number, r.status, r.refund_amount, r.currency,
       r.refund_failure, r.received_at
  FROM return_requests r
 WHERE r.status = 'RECEIVED' AND r.refund_failure IS NOT NULL
 ORDER BY r.received_at;

-- Stuck asking rather than failed: the command went out and no answer came back.
SELECT order_number, refund_amount, currency, received_at
  FROM return_requests
 WHERE status = 'REFUND_PENDING' AND received_at < now() - interval '1 hour';
```

`REFUND_PENDING` for more than a few minutes means the reply never arrived, not that the refund
failed. Check the outbox in `commerceflow_order` and the consumer lag on
`return.refund.commands` (section 3) before touching anything — the money may already have moved.

```sql
-- commerceflow_payment: what actually happened at the acquirer
SELECT amount, currency, succeeded, external_reference, failure_reason, created_at
  FROM payment_refunds
 WHERE idempotency_key = '<return-id>';
```

That row is the truth. Three cases, and they need different answers:

| `payment_refunds` | What it means | What to do |
|---|---|---|
| `succeeded = true` | The money went. The reply was lost. | Republish the reply from the payment outbox. Do **not** press refund again — it would be a no-op, but confirm first. |
| `succeeded = false` | The acquirer refused it. | Read `failure_reason`. Usually a closed card. Refund by another route and record it. |
| No row at all | The command never arrived. | Press refund again. The return id is a unique key there, so it cannot double-refund. |

**Pressing refund twice is safe, and that is by design.** Payment Service keys on the return id
with a unique constraint, so a repeated or republished command finds the existing row and answers
from it without touching the acquirer.

**A manual refund at the provider does not update this database.** Same trap as section 11: record
it, and treat the gap as something to fix rather than repeat.

### Returned stock that never reappeared

Separate failure, same feature. When goods are received as resellable, Order Service sends a
restock command and Inventory Service puts those units back into the building they shipped from.
There is no reply, so a failure shows up in the dead-letter topic and the audit log rather than on
the return.

```sql
-- commerceflow_order: returns whose goods were meant to go back on sale
SELECT order_number, received_at, restocked
  FROM return_requests
 WHERE restocked = TRUE AND received_at > now() - interval '7 days';

-- commerceflow_inventory: what actually went back
SELECT occurred_at, target_id, summary
  FROM admin_audit_log
 WHERE action = 'RETURN_RESTOCKED'
 ORDER BY occurred_at DESC LIMIT 50;
```

A return marked `restocked = TRUE` with no matching `RETURN_RESTOCKED` audit entry means the units
are physically back and not on any shelf. Check `return.restock.commands.DLT` (section 4). The
usual causes are a warehouse deactivated since the order shipped, or a product deleted from the
catalogue — both are logged with the product and warehouse id.

`restocked = FALSE` is not a failure. It means somebody opened the box and decided the goods could
not be sold again.

---

## Docker Compose equivalents

```bash
docker compose ps
docker compose logs -f order-service
docker compose exec postgres psql -U commerceflow -d commerceflow_order
docker compose exec redis redis-cli
docker compose exec kafka kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --all-groups
```

Kafka UI at http://localhost:8090 is the fastest way to inspect topics, lag and dead letters
locally.

---

## Escalate when

- An order is stuck in `PAID` — that is a bug in Order Service, not an outage.
- `GATEWAY_ERROR` payments are accumulating — money may have moved without the platform knowing.
- Stock reconciliation shows reserved units with no matching reservation row — the invariant that
  makes compensation exact has been violated.
