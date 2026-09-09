# Load testing

One test, one question: **does the stock lock hold, and what does it cost?**

`InventoryReservationService` takes a `PESSIMISTIC_WRITE` lock on the `stock_levels` row before
it decrements. That is what makes overselling impossible — and it is also a queue. Every order
for the same product waits its turn on one row, no matter how many service instances are
running. On an ordinary Tuesday nobody notices. On a launch day, when a thousand people want the
same thing in the same minute, it is the first thing that will bend.

## Running it

```bash
docker compose up -d
./scripts/seed-demo-data.sh
k6 run load-test/checkout-contention.js
```

Install k6 from <https://k6.io/docs/get-started/installation/> — it is a single binary.

```bash
k6 run -e RATE=80 -e DURATION=3m load-test/checkout-contention.js
k6 run -e GATEWAY_URL=https://staging-api.commerceflow.io load-test/checkout-contention.js
```

**Never point it at production.** It places real orders, and the compensation branch issues real
refunds.

## How to read the result

The test runs the same order rate twice: once with every order for the same product
(`hot_product`), once spread across ten (`spread`). The second run is the control. It uses the
same gateway, saga, database and machine, so whatever it costs is the baseline cost of checkout;
whatever the first run costs *on top of that* is the lock.

Without the control you cannot tell a slow lock from a slow laptop, which is why almost every
number below is a comparison rather than an absolute.

| Metric | What it tells you |
|---|---|
| `order_accept_hot` vs `order_accept_spread` | The cost of contention. These start out close and diverge as the rate climbs. The rate where `hot` breaks away from `spread` is the answer you came for. |
| `order_settle_hot` / `order_settle_spread` | What the customer actually experiences. `POST /api/orders` returns as soon as the order is accepted; reservation, payment and confirmation all happen afterwards. Accept latency alone would report a system that is instantly fast and hours behind. |
| `orders_rejected_out_of_stock` | Correct behaviour, not failure — the lock refusing to sell what is not there. A large number means the shelf ran out partway through and the rest of the run measured the wrong path. |
| `orders_rejected_other` | Must be zero. Anything else is a bug, not load. |
| `order_settle_failed` | Sagas that compensated or never finished. A few under heavy load is the system protecting itself; a rising rate is the saga timing out. |
| `available stock never went negative` | The one that matters. See below. |

### Finding the knee

Run it at increasing rates and write down the two accept figures each time:

```bash
for rate in 10 20 40 80 160; do
  k6 run -e RATE=$rate -e DURATION=90s load-test/checkout-contention.js \
    --summary-export="load-test/results-$rate.json"
done
```

`spread` will rise gently — that is the machine filling up. `hot` will track it and then leave
it. Where the two separate is the point at which the single-row lock, not the hardware, is what
limits the shop. Below that rate, adding service instances helps. Above it, they queue on the
same row and add nothing.

That number is worth knowing *before* marketing picks a launch date.

## If it says OVERSOLD

Stop. Nothing else in the report matters.

It means an order was accepted for stock that was not there, and the failure does not show up
during the run — it shows up days later as an order nobody can fulfil, placed by a customer who
has already been charged. Check that the reservation still goes through the locking path, that
nothing added a read-then-write around it, and that no new code path decrements stock without
taking the lock first.

## What this does not cover

Being explicit, because a passing load test invites the belief that the system is proven and
this one covers a single path:

- **Browsing and search.** The read side is cached and served from a read model; it fails
  differently and needs its own test.
- **Sustained load.** Two minutes finds contention. It does not find connection-pool leaks,
  memory growth or an outbox table that stops fitting in cache — those need hours.
- **Failure under load.** Killing a service mid-run is where saga timeouts and idempotency
  actually get tested. Worth doing, deliberately, once this passes.
- **The payment provider.** Stripe is in test mode and its latency is not your production
  latency.

## Recorded results

Fill this in as you run it. A number from six months ago on different hardware is worse than no
number, so keep the date and the machine next to it.

| Date | Environment | Rate | `hot` p95 accept | `spread` p95 accept | Oversell | Notes |
|---|---|---|---|---|---|---|
| _(not yet run)_ | | | | | | |
