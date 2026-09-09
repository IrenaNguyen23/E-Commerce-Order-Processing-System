/**
 * Runtime configuration, read once from Vite env vars.
 *
 * Centralised so no module reaches into `import.meta.env` directly — that keeps every knob
 * discoverable and gives one place to validate them at boot.
 */

const readNumber = (value: string | undefined, fallback: number): number => {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
};

export const config = {
  appName: import.meta.env.VITE_APP_NAME || 'CommerceFlow',

  /**
   * Empty in development on purpose: requests go to a relative `/api`, which the Vite proxy
   * forwards to the gateway. Same-origin in dev and in production means no CORS anywhere.
   */
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL || '',

  apiTimeout: readNumber(import.meta.env.VITE_API_TIMEOUT, 15_000),

  /**
   * Stripe publishable key. Safe in the bundle by design — it can only create payment attempts,
   * never read or move money. Empty when the backend is on the simulated acquirer, and the
   * payment form is skipped entirely rather than mounted against nothing.
   */
  stripePublishableKey: import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY || '',

  isDev: import.meta.env.DEV,
  isProd: import.meta.env.PROD,
} as const;

/** Storage keys, namespaced so several apps can share an origin without colliding. */
export const storageKeys = {
  auth: 'cf.auth',
  cart: 'cf.cart',
  wishlist: 'cf.wishlist',
  addresses: 'cf.addresses',
} as const;

/**
 * How long a paged list waits before it is considered stale.
 * The catalogue changes rarely; orders are polled explicitly while their saga runs.
 */
export const staleTimes = {
  catalogue: 5 * 60 * 1000,
  order: 0,
  session: 60 * 1000,
} as const;

/** The interval at which an in-flight order saga is polled. */
export const ORDER_POLL_INTERVAL_MS = 2_000;

/** Give up polling after this long and tell the user to check back — never spin forever. */
export const ORDER_POLL_TIMEOUT_MS = 90_000;

export const DEFAULT_PAGE_SIZE = 12;
export const ADMIN_PAGE_SIZE = 20;
export const MAX_ORDER_ITEMS = 50;
export const MAX_ITEM_QUANTITY = 999;
