/*
 * Checkout under contention.
 *
 * ============================================================================================
 * THE QUESTION THIS ASKS
 * ============================================================================================
 *
 * InventoryReservationService takes a PESSIMISTIC_WRITE lock on the stock_levels row before it
 * decrements. That is what makes overselling impossible, and it is also a queue: every order for
 * the same product waits its turn on one row, however many service instances are running.
 *
 * Which is fine until a launch day, when a thousand people want the same thing in the same
 * minute. So this test asks two things at once, and they pull in opposite directions:
 *
 *   1. Does the lock hold?  -- zero oversell, always, at any rate. Not negotiable.
 *   2. What does it cost?   -- where the queue on one row starts showing up as latency.
 *
 * A load test that only measured throughput would happily report a fast system that sold
 * eleven of the ten laptops it had.
 *
 * ============================================================================================
 * HOW IT ANSWERS IT
 * ============================================================================================
 *
 * Two scenarios at the same arrival rate, run one after the other:
 *
 *   hot_product   every order is for the SAME product -- fully serialised on one row
 *   spread        the same orders spread over ten products -- the lock is barely contended
 *
 * The comparison is the measurement. `spread` is the control: it uses the same gateway, the same
 * saga, the same database and the same machine, so whatever it costs is the baseline cost of
 * checkout. Whatever `hot_product` costs on top of that is the lock. Without the control you
 * cannot tell a slow lock from a slow laptop.
 *
 * ============================================================================================
 * RUNNING IT
 * ============================================================================================
 *
 *   docker compose up -d
 *   ./scripts/seed-demo-data.sh
 *   k6 run load-test/checkout-contention.js
 *
 *   k6 run -e RATE=80 -e DURATION=3m load-test/checkout-contention.js
 *   k6 run -e GATEWAY_URL=https://staging-api.commerceflow.io load-test/checkout-contention.js
 *
 * Never point it at production. It places real orders, and the compensation branch refunds real
 * money.
 */

import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const GATEWAY = __ENV.GATEWAY_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 40); // orders per second, per scenario
const DURATION = __ENV.DURATION || '2m';

/* Seeded by scripts/seed-demo-data.sh. The hot product is the 14" laptop because it is stocked
 * deep enough to absorb a two-minute run; the limited dock has two units and would be sold out
 * in the first second, which measures the out-of-stock path rather than the lock. */
const HOT_PRODUCT = '11111111-1111-1111-1111-111111111101';

const SPREAD_PRODUCTS = [
  '11111111-1111-1111-1111-111111111102',
  '11111111-1111-1111-1111-111111111103',
  '11111111-1111-1111-1111-111111111104',
  '11111111-1111-1111-1111-111111111105',
  '11111111-1111-1111-1111-111111111106',
  '11111111-1111-1111-1111-111111111108',
  '11111111-1111-1111-1111-111111111111',
  '11111111-1111-1111-1111-111111111113',
  '11111111-1111-1111-1111-111111111117',
  HOT_PRODUCT,
];

const ADDRESS = {
  recipientName: 'Load Test',
  phone: '+31 20 123 4567',
  line1: 'Keizersgracht 1',
  city: 'Amsterdam',
  postalCode: '1015 CJ',
  countryCode: 'NL',
};

/* -------------------------------------------------------------------------------------------
 * Metrics
 *
 * Split by scenario, because the whole point is the difference between them. A single blended
 * p95 across both would average the contention away.
 * ---------------------------------------------------------------------------------------- */

const acceptHot = new Trend('order_accept_hot', true);
const acceptSpread = new Trend('order_accept_spread', true);
const settleHot = new Trend('order_settle_hot', true);
const settleSpread = new Trend('order_settle_spread', true);

const accepted = new Counter('orders_accepted');
const rejectedOutOfStock = new Counter('orders_rejected_out_of_stock');
const rejectedOther = new Counter('orders_rejected_other');
const guestFailures = new Counter('guest_session_failures');
const settleTimeouts = new Counter('orders_never_settled');
const settleFailureRate = new Rate('order_settle_failed');

export const options = {
  scenarios: {
    hot_product: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      /* Generous, on purpose. If the lock queues requests, k6 needs spare VUs to keep issuing
       * new ones at the configured rate -- otherwise it silently drops the rate instead, and
       * the graph shows a system coping when it is actually saturated. */
      preAllocatedVUs: RATE * 4,
      maxVUs: RATE * 20,
      exec: 'placeOnHotProduct',
      tags: { contention: 'hot' },
    },
    spread: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: RATE * 4,
      maxVUs: RATE * 20,
      exec: 'placeOnSpreadProducts',
      tags: { contention: 'spread' },
      /* Runs after the hot scenario rather than beside it. Concurrently, the two would compete
       * for the same connection pool and each would be measuring the other. */
      startTime: DURATION,
    },
  },

  thresholds: {
    /* The correctness thresholds. These are what makes this a test rather than a benchmark. */
    'orders_rejected_other': ['count == 0'],
    'guest_session_failures': ['count == 0'],
    'checks{kind:oversell}': ['rate == 1.00'],

    /* The performance ones. Deliberately loose -- they catch a collapse, not a regression.
     * Tighten them once you have a number from your own hardware; a threshold copied from
     * somebody else's laptop fails for reasons that have nothing to do with the code. */
    'order_accept_hot': ['p(95) < 2000'],
    'order_accept_spread': ['p(95) < 1000'],
    'order_settle_failed': ['rate < 0.02'],
    'http_req_failed': ['rate < 0.05'],
  },
};

/* -------------------------------------------------------------------------------------------
 * Setup
 * ---------------------------------------------------------------------------------------- */

export function setup() {
  const health = http.get(`${GATEWAY}/actuator/health`);
  if (health.status !== 200) {
    fail(`the gateway at ${GATEWAY} is not up (${health.status}). Start the stack first.`);
  }

  /* Stock before the run, so teardown can prove the lock held. A run with no errors in it has
   * still oversold if the stock went negative, and nothing in the HTTP responses would say so. */
  const before = stockOf(HOT_PRODUCT);
  if (before === null) {
    fail(`could not read the hot product ${HOT_PRODUCT}. Run ./scripts/seed-demo-data.sh first.`);
  }

  console.log(`Hot product: ${before.available} available, ${before.reserved} reserved.`);

  /* Roughly how many units the run will try to consume. If the shelf is thinner than that, the
   * product sells out partway through and everything after that measures the out-of-stock path
   * -- which is a real path, but not the one this test is for. */
  const wanted = RATE * durationSeconds(DURATION);
  if (before.available < wanted) {
    console.warn(
      `WARNING: ${before.available} units will not cover ~${wanted} orders. Once it sells out ` +
        `this measures the out-of-stock path instead of the lock. Re-seed, or lower RATE.`,
    );
  }

  return { stockBefore: before };
}

/* -------------------------------------------------------------------------------------------
 * The two scenarios
 * ---------------------------------------------------------------------------------------- */

export function placeOnHotProduct() {
  placeOrder(HOT_PRODUCT, acceptHot, settleHot);
}

export function placeOnSpreadProducts() {
  const product = SPREAD_PRODUCTS[exec.scenario.iterationInTest % SPREAD_PRODUCTS.length];
  placeOrder(product, acceptSpread, settleSpread);
}

function placeOrder(productId, acceptMetric, settleMetric) {
  const token = guestSession();
  if (!token) return;

  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
  };

  const body = JSON.stringify({
    items: [{ productId, quantity: 1 }],
    shippingAddress: ADDRESS,
    shippingMethod: 'STANDARD',
    /* A fresh key each time. Reusing one would have the idempotency ledger return the first
     * order and never touch the lock at all -- a very fast test of nothing. */
    idempotencyKey: `load-${exec.scenario.name}-${exec.scenario.iterationInTest}-${__VU}`,
  });

  const response = http.post(`${GATEWAY}/api/orders`, body, {
    headers,
    tags: { name: 'POST /api/orders' },
  });

  acceptMetric.add(response.timings.duration);

  if (response.status === 201) {
    accepted.add(1);
    const orderId = json(response, 'data.id');
    if (orderId) awaitSettlement(orderId, token, settleMetric);
    return;
  }

  /* Out of stock is a correct answer, not a failure: it means the lock did its job and the
   * shop refused to sell what it did not have. Counted separately so a run that quietly sold
   * out is visible rather than looking like a run of errors. */
  if (response.status === 409 || response.status === 422) {
    rejectedOutOfStock.add(1);
    return;
  }

  rejectedOther.add(1);
  console.error(`order rejected: ${response.status} ${String(response.body).slice(0, 300)}`);
}

/* -------------------------------------------------------------------------------------------
 * Following the saga
 *
 * POST /api/orders returns as soon as the order is accepted -- the reservation, the payment and
 * the confirmation all happen afterwards. Accept latency alone would therefore report a system
 * that is instantly fast and hours behind. What a customer experiences is the settle time.
 * ---------------------------------------------------------------------------------------- */

function awaitSettlement(orderId, token, settleMetric) {
  const started = Date.now();
  const deadline = started + 30000;
  const headers = { Authorization: `Bearer ${token}` };

  while (Date.now() < deadline) {
    const response = http.get(`${GATEWAY}/api/orders/${orderId}`, {
      headers,
      tags: { name: 'GET /api/orders/{id}' },
    });

    const status = json(response, 'data.status');
    if (status === 'COMPLETED' || status === 'CANCELLED') {
      settleMetric.add(Date.now() - started);
      settleFailureRate.add(status === 'CANCELLED');
      return;
    }

    /* Polling, because that is what the front end does. Under an arrival-rate executor a
     * sleeping VU does not slow the offered rate -- k6 starts another one -- so this costs
     * accuracy nowhere and keeps the generator from spending its CPU on the poll loop. */
    sleep(0.25);
  }

  settleTimeouts.add(1);
  settleFailureRate.add(true);
}

/* -------------------------------------------------------------------------------------------
 * Teardown -- the oversell check
 * ---------------------------------------------------------------------------------------- */

export function teardown(data) {
  const after = stockOf(HOT_PRODUCT);

  if (after === null) {
    console.warn('Could not read stock afterwards, so oversell was NOT verified. Check by hand.');
    return;
  }

  const before = data.stockBefore;
  const consumed = before.available - after.available;

  console.log(
    `Hot product: ${before.available} -> ${after.available} available ` +
      `(${consumed} consumed), ${after.reserved} still reserved.`,
  );

  /* Negative availability is the failure this whole test exists to catch: the shop sold
   * something it did not have. It surfaces much later, as an order nobody can fulfil placed by
   * a customer who has already paid. */
  check(after, {
    'available stock never went negative': (value) => value.available >= 0,
  }, { kind: 'oversell' });

  if (after.available < 0) {
    console.error(
      `OVERSOLD by ${-after.available} units. The PESSIMISTIC_WRITE lock on stock_levels did ` +
        `not hold. Stop and find out why -- nothing else in this report matters until it is fixed.`,
    );
  }

  /* Units still reserved when the run ends are sagas that had not finished yet, which is
   * expected for the last few seconds. A large number is not: it means reservations are being
   * taken and never released, and they will time out into compensation later.
   *
   * The reservation sweeper is what should reclaim them. If they are still there tomorrow, the
   * sweeper is the thing to look at -- runbook.md, "stock drift".
   */
  if (after.reserved > RATE * 5) {
    console.warn(
      `${after.reserved} units are still reserved. Expect a handful of in-flight sagas; this ` +
        `many suggests reservations are not being released. Re-check in a few minutes.`,
    );
  }
}

/* "2m" / "90s" / "1h" -> seconds. Only used to size the stock warning, so it errs toward
 * warning rather than staying silent on a format it does not recognise. */
function durationSeconds(duration) {
  const match = /^(\d+)([smh])$/.exec(String(duration).trim());
  if (!match) return Number.MAX_SAFE_INTEGER;
  const value = Number(match[1]);
  return match[2] === 'h' ? value * 3600 : match[2] === 'm' ? value * 60 : value;
}

/* -------------------------------------------------------------------------------------------
 * Helpers
 * ---------------------------------------------------------------------------------------- */

function guestSession() {
  /* A new guest per order. Reusing one account would serialise every order behind that
   * customer's own basket and cart rows, and the test would measure the wrong lock. */
  const email = `load-${__VU}-${exec.scenario.iterationInTest}-${exec.scenario.name}@load.invalid`;

  const response = http.post(
    `${GATEWAY}/api/auth/guest`,
    JSON.stringify({ email, fullName: 'Load Test' }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'POST /api/auth/guest' } },
  );

  if (response.status !== 201) {
    guestFailures.add(1);
    console.error(`guest session failed: ${response.status} ${String(response.body).slice(0, 200)}`);
    return null;
  }

  return json(response, 'data.accessToken');
}

/*
 * Available and reserved, read together.
 *
 * They are two halves of one figure: a unit an order is holding has left `available` and shows
 * up in `reserved`, and only when the saga completes does it leave both. Reading available on
 * its own during a run would look like stock vanishing.
 */
function stockOf(productId) {
  const response = http.get(`${GATEWAY}/api/products/${productId}`, {
    tags: { name: 'GET /api/products/{id}' },
  });
  if (response.status !== 200) return null;

  const available = json(response, 'data.availableQuantity');
  const reserved = json(response, 'data.reservedQuantity');
  if (typeof available !== 'number') return null;

  return { available, reserved: typeof reserved === 'number' ? reserved : 0 };
}

function json(response, path) {
  try {
    return path.split('.').reduce((node, key) => (node == null ? null : node[key]),
      response.json());
  } catch (error) {
    return null;
  }
}
