# Data retention

What CommerceFlow keeps, for how long, and why.

Two things go wrong without a written policy, and they pull in opposite directions. Tables grow
until an index no longer fits in memory and every query pays for rows nobody will ever read. And
personal data accumulates that the platform has no reason to hold, which is a liability the day
somebody asks for it or the day the database leaks.

The rule underneath every decision below: **keep what answers a question somebody will actually
ask; delete what has stopped being useful.** Where those two conflict — most often between an
accountant and a privacy regulator — the financial record wins and the person inside it is
anonymised instead. That is what erasure does; see [Personal data](#personal-data-and-erasure).

---

## What is swept, and when

Eight jobs. Each is a plain `DELETE` with a cutoff, runs on a schedule, and logs how many rows it
removed.

| Job | Table | Keeps | Runs | Configured by |
|---|---|---|---|---|
| Outbox sweeper | `outbox_event` (`PUBLISHED` only) | 7 days | hourly | `commerceflow.outbox.retention` |
| Idempotency sweeper | `processed_event` | 7 days | hourly | `commerceflow.idempotency.retention` |
| Verification token sweeper | `verification_tokens` (expired only) | 7 days | hourly | `commerceflow.auth.verification-token-retention` |
| Refresh token sweeper | `refresh_tokens` (expired only) | 7 days | hourly | `commerceflow.auth.refresh-token-retention` |
| Audit sweeper | `admin_audit_log` | 730 days | 03:30 daily | `commerceflow.audit.retention` |
| Cart sweeper | `carts` (+ `cart_items` by cascade) | 120 days | 04:00 daily | `commerceflow.order.abandoned-cart-retention` |
| Saga history sweeper | `saga_instance`, `saga_step_log` | 90 days | 04:15 daily | `commerceflow.order.saga-history-retention` |
| Notification sweeper | `notifications` (`SENT` only) | 365 days | 04:45 daily | `commerceflow.notification.retention` |

Each period, and why it is that number rather than another:

**Outbox — 7 days.** A published row has already done its job. It is kept only long enough to
answer "was this event actually sent, and when", which is a question asked during an incident and
almost never afterwards. Unpublished rows are never swept at any age — they are undelivered work.

**Idempotency ledger — 7 days.** This one has a hard floor: it **must comfortably exceed the Kafka
topic retention**. The ledger is what stops a redelivered message being processed twice, so if a
claim is deleted while the message it guards can still be replayed, the guarantee is gone. Raising
Kafka's retention without raising this is a silent way to reintroduce double-processing.

**Verification and refresh tokens — 7 days past expiry.** A short grace period so a support
question asked the next morning still has a row to look at. Both sweeps take only *expired* rows.
A revoked-but-unexpired refresh token stays: it is what makes a sign-out stick, and deleting it
early turns "this session was ended" into "no such session", which the refresh endpoint cannot
tell apart from a token it has never seen.

**Admin audit log — 730 days, and never less than 30.** Two years, because that is the horizon
over which somebody asks "who changed this, and when" — a dispute, an investigation, an external
audit. Anyone with a statutory period should set this from that number rather than inheriting a
default. The log records *what* changed, deliberately not the before and after values: keeping
those would mean keeping personal data in a table with a two-year retention, which is the opposite
of the point.

The 30 day floor is enforced by a database trigger, not by convention. `admin_audit_log` refuses
every `UPDATE`, and refuses to delete any entry younger than thirty days — which is what stops the
person an entry describes from removing it. The nightly sweep is unaffected, because everything it
deletes is two years old.

**So `commerceflow.audit.retention` must not be set below 30 days.** Do it and the sweep starts
failing against the trigger every night. Failing loudly is the intended behaviour: the alternative
is a trigger that quietly permits what it was added to prevent.

**Baskets — 120 days.** A basket abandoned four months ago is not a basket anybody is coming back
to; it is a list of what somebody was thinking of buying. Baskets hold no prices — everything a
basket page shows is read live from the catalogue — so deleting one loses nothing but the list.

**Saga history — 90 days.** A finished saga is a debugging artefact. Three months covers "what
happened to this order last quarter" and stops the step log, which is the fastest-growing table in
the system, from becoming the largest. **`STALLED` sagas are never swept, at any age** — see
below.

**Notifications — 365 days.** A year of "did we send it, and when", which is the question support
asks. Only `SENT` rows go; see below.

---

## What is never deleted

Some of these look like omissions. They are decisions.

### Never swept because it is the record of a failure

- **`STALLED` sagas** — a parked saga is an unfinished piece of business. It is *why* an order is
  in the state it is in, and deleting it destroys the only account of what went wrong. They should
  be resolved, not aged out.
- **`FAILED` and `PENDING` notifications** — the evidence of every message the platform failed to
  deliver. A sweeper that removed them would quietly erase the record of a broken mail
  configuration, which is precisely the thing somebody needs to find.
- **Unpublished `outbox_event` rows** — undelivered work, not history.
- **`payments`, whatever their status** — never deleted at all. A `FAILED` row with a gateway
  failure reason is where money may have moved without the platform knowing, and it is the
  starting point of every reconciliation against the provider.

### Never swept because it is the business record

`orders`, `order_items`, `order_view`, `payments`, `payment_refunds`, `shipments`,
`return_requests`, `return_request_items`, `coupon_redemptions`, `product_reviews`, `products`,
`stock_levels`, `warehouses`.

These are what the shop *is*. An order from four years ago still has to add up for an accountant,
a review is content other customers are reading, and a redemption row is the record of what a
campaign cost. A return and its refund are the other half of a sale: an order whose money came
back but whose return was deleted reads as a sale that vanished. They grow slowly and in proportion to the business, which is the kind of growth a
database is for. When they eventually need managing, the answer is archival to cold storage —
not deletion.

### `shipment_events` — a deliberate exception

Every scan of a parcel writes a row, and the table grows faster than `shipments` does, so it is
the obvious next candidate for a sweeper. It does not get one.

A shipment's event history *is* the shipment. Without it, `status = 'DELIVERED'` is an assertion
with nothing behind it — you cannot say when it was dispatched, where it was scanned, or how many
delivery attempts were made. That history is what settles a "it never arrived" dispute, and those
arrive months late. It belongs to the order record, and the order record is kept.

If it ever does need managing, archive it with its parent shipment rather than sweeping it by age.
Half a parcel's history is worse than none: it reads as a complete account and is not.

### `inventory_reservations` and `reservation_items`

No sweeper, and there should probably be one eventually. They are the audit trail of what was held
for which order, and a released reservation keeps its row rather than disappearing — so the table
grows roughly with order volume. `StaleReservationMonitor` reports old `RESERVED` rows but
deliberately never deletes or releases them; releasing stock for an order that turns out to be
about to pay is worse than the alert.

---

## Personal data and erasure

Retention periods are the *scheduled* deletion. `DELETE /api/users/{id}` is the requested one, and
it works differently: it **anonymises rather than deletes**, because orders reference the account
and orders have to survive.

| Data | On erasure |
|---|---|
| Account | Kept, anonymised. Email becomes `erased-<uuid>@erased.invalid` (RFC 2606 reserves `.invalid`, so it can never become a real person's address), name becomes a placeholder, phone removed, password replaced with a hash nobody knows, sessions revoked. |
| Saved addresses | Deleted outright. Only ever personal, nothing references them. |
| Orders and the read model | Kept, anonymised. Email, recipient name, street and postcode go. **The country stays** — it chose the tax rate, and an invoice that cannot say which country it was for cannot be explained. A country alone identifies nobody. |
| Basket and wishlist | Deleted outright. |
| Reviews | Kept, byline anonymised. The words stay: other customers are reading them and the product rating depends on them. Deleting a review because its author closed their account would silently change the shop's ratings. |
| Verified purchases | Deleted outright. A record of what a named person bought, which nothing needs after the fact. |
| Audit entries | **Kept as written**, including the actor's email. An audit log that can be edited by the person it describes is not an audit log. |

Erasure propagates by the `user.erased` event, so each service scrubs its own copy on its own
schedule. Runbook section 15 covers checking that it landed everywhere.

---

## Changing a period

Every period is a configuration property, not a constant. Set it per environment:

```yaml
commerceflow:
  audit:
    retention: 2555d          # seven years, if that is your statutory period
  order:
    saga-history-retention: 30d
```

Two failure modes to avoid:

- **Raising Kafka topic retention without raising `commerceflow.idempotency.retention`.** The
  ledger must outlive anything that can still be redelivered, or duplicates stop being caught.
- **Lowering a retention to shrink a table.** The first sweep after the change deletes everything
  between the old cutoff and the new one, in one transaction. On a large table that is a long
  lock. Step it down over several days.

### Legal hold

When data must not be deleted — litigation, an investigation, a regulator's request — stop the
job rather than setting an enormous retention:

```yaml
commerceflow:
  audit:
    cleanup-enabled: false
```

A very large number is a setting somebody will later "tidy up" without knowing why it was there.
A disabled job is a question that has to be answered before it is re-enabled. The outbox and
idempotency sweeps have the same switch (`commerceflow.outbox.cleanup-enabled`,
`commerceflow.idempotency.cleanup-enabled`).

---

## Backups have their own clock

Deleting a row does not delete it from the backups taken before the sweep. `scripts/backup.sh`
keeps 14 days by default (`RETENTION_DAYS`), so anything swept is still recoverable — and still
*held* — for up to two weeks afterwards.

That matters for an erasure request: the account is anonymised immediately in the live system, and
the pre-erasure copy persists in backups until they age out. This is the ordinary and accepted
position (a backup is not a live processing system), but it needs saying rather than being
discovered. Never restore an old backup to recover a single record without re-applying the
erasures made since.

---

## Known gaps

Written down rather than left to be discovered:

- **The sweepers are not leader-elected.** With more than one replica of a service, every replica
  runs every job. The deletes are idempotent, so the result is correct and the work is duplicated
  — worth fixing before scaling out, not after.
- **No sweeper for `inventory_reservations`.** Discussed above; it grows with order volume.
- **No archival tier.** The policy is "keep or delete". A shop that runs for years will eventually
  want the business tables in cold storage, and nothing here does that.
- **Nothing alerts when a sweep stops running.** A table simply grows, which is a failure
  discovered six months late. Runbook section 17 has the query to check by hand; an alert on the
  oldest row in each table would be better.
- **The append-only trigger does not stop a superuser.** Dropping the trigger, or `TRUNCATE`
  (which does not fire row triggers), both bypass it. Neither is the threat it was written for —
  that is an operator with ordinary application-level access editing the record of what they did,
  and that is closed. Real tamper-evidence would mean shipping entries to storage this platform
  cannot write to.
- **Reading the trail is an administrator's privilege.** There is no separate auditor role, so
  somebody who only needs to read the log has to be given the ability to change things. Separation
  of duties needs a role that does not exist yet.
