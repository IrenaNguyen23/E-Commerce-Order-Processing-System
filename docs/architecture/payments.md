# Payments

What changes when the acquirer is real, and where the customer actually pays.

---

## 1. The shape of the problem

The simulated acquirer decides on the spot. `charge()` is called, it returns approved or declined,
and the payment row plus the saga reply are written in the same transaction. Clean, synchronous,
and **not how card payments work.**

A real charge can need 3-D Secure, a wallet confirmation, or a bank that takes hours. Stripe
answers "processing" and tells you the outcome later, over a webhook. Two things follow, and both
are now built:

- `ChargeResult` has three outcomes, not two. `PENDING` means *ask again later, or wait to be
  told*. **Never "assume it failed"** — a saga that reads pending as declined cancels orders after
  the customer's money has left their account.
- The payment row stores `saga_id` and `command_id`. The webhook that finally settles a charge
  arrives with no command anywhere near it, and a reply without that linkage is one the
  orchestrator drops — leaving the order stuck behind a charge that actually succeeded.

---

## 2. The line that makes retries safe

The orchestrator re-sends an unanswered `PROCESS_PAYMENT` up to three times. Against the simulator
that costs nothing. Against Stripe it would be three charges.

```java
RequestOptions.builder().setIdempotencyKey("order-" + orderId).build()
```

Stripe returns the **original** PaymentIntent for a repeat with the same key rather than creating
another. The key is derived from the order, not the command, because two *different* commands for
one order are exactly the case that must not produce two charges — and a per-command key would
happily create one each.

Three defences, in order:

| Layer | Stops |
|---|---|
| Idempotency ledger | The same command being processed twice |
| `payments.order_id` unique index | Two payment rows for one order |
| Stripe idempotency key | Two charges at the acquirer |

Any one of them failing still leaves two others. That is deliberate: this is the only place in the
platform where a bug costs real money.

---

## 3. Money, in the units the acquirer expects

Stripe quotes in minor units, so €18.99 is `1899`. Except where it does not: **VND, JPY and
thirteen other currencies are zero-decimal**, and multiplying those by 100 charges the customer a
hundred times the price. Nothing in the type system objects, and the customer is the one who
discovers it.

`StripePaymentGateway.toMinorUnits` holds the list, and `StripeAmountConversionTest` pins it. If
this platform ever sells in dong, that test is the reason it does not overcharge.

---

## 4. The webhook

`POST /api/payments/webhook` is public and marks orders paid. What stands in for authentication is
Stripe's signature over the **raw request body** — which is why the controller takes a `String`
rather than a bound object. Verifying a re-serialised reconstruction would be checking something
Stripe never signed.

The service refuses to start if `STRIPE_WEBHOOK_SECRET` is missing. An unsigned endpoint that
marks orders paid is worse than no endpoint at all, and start-up is the last moment anyone notices
it is absent.

Unrecognised events and unknown payment references get `200`. Stripe retries anything else for
days, and a staging account sharing a webhook with production would generate a retry storm over
events that are correctly being ignored. A `4xx` is reserved for a body that fails its signature.

---

## 5. How the customer actually pays

Built as **Option A**: the order is placed, stock is reserved, and the customer completes payment
on the order screen. The property that makes this saga safe — *stock is held before money moves* —
survives, and the alternative (pay first, place the order after) would have traded a compensation
that always works, giving stock back, for one that sometimes does not: refunds.

```
POST /api/orders          201, stock reserved, PaymentIntent created → PENDING
   │
   ▼
Order screen              polls the order; picks up the payment and its client secret,
   │                      mounts Stripe Elements, customer pays
   ▼
Stripe → webhook          payment.succeeded → saga advances → order COMPLETED
```

The browser never marks anything paid. Confirming tells Stripe; Stripe tells the backend; the
backend advances the saga. The page notices because it was already polling. Anything else would be
trusting a browser about whether money moved.

The client secret is stored on the payment row and returned **only to its owner, and only while
the charge is outstanding**. It authorises paying that one intent and nothing else, which is what
makes it safe to hand to a browser at all.

### The abandoned basket

This flow makes one thing routine that used to be an anomaly: a customer who places an order and
never pays. The order exists, stock is held, and nobody is coming back.

Left alone, every one of those would exhaust the payment step's retries and **park a saga for an
operator** — turning ordinary shopping behaviour into a pager alert. So a PaymentIntent still in
`requires_payment_method` after `abandon-after` (default 10 minutes) is treated as a decline with
reason `PAYMENT_ABANDONED`, and the saga compensates the ordinary way: stock back, order
cancelled, customer told.

Only `requires_payment_method` counts. A customer part-way through 3-D Secure is
`requires_action`, and cancelling those would fail payments that were seconds from succeeding.

**These two timings are coupled, and getting them the wrong way round breaks it:**

| Setting | Default | With Stripe |
|---|---|---|
| `SAGA_STEP_TIMEOUT` | 2 min | **5 min** |
| `SAGA_MAX_ATTEMPTS` | 3 | 3 |
| `commerceflow.payment.stripe.abandon-after` | 10 min | 10 min |

The retry budget (timeout × attempts) has to comfortably exceed `abandon-after`, or the saga parks
before the abandonment check ever runs and the operator gets paged anyway. At the defaults above:
15 minutes of retries against a 10-minute abandonment window.

---

## 6. Configuration

```bash
# Backend
PAYMENT_GATEWAY=STRIPE
STRIPE_SECRET_KEY=sk_test_...        # secret key, never the publishable one
STRIPE_WEBHOOK_SECRET=whsec_...      # from the webhook endpoint in the Stripe dashboard
SAGA_STEP_TIMEOUT=5m                 # see the coupling table above

# Frontend, at build time — Vite inlines it
STRIPE_PUBLISHABLE_KEY=pk_test_...   # publishable, and safe in the bundle: it can only create
                                     # payment attempts, never read or move money
```

Both are required when the gateway is `STRIPE`; the service refuses to start otherwise. The
default is `SIMULATED`, so `docker compose up` still gives a working system to someone with no
Stripe account.

Locally, `stripe listen --forward-to localhost:8080/api/payments/webhook` prints a signing secret
that works with the Stripe CLI's test events.
