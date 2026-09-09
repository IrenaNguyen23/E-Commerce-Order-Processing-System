/**
 * Every route in the app.
 *
 * Referenced instead of literal strings so renaming a route is one edit and a typo is a compile
 * error rather than a silent 404. Parameterised routes expose both the pattern (for the router)
 * and a builder (for links).
 */
export const paths = {
  home: '/',

  // ---- customer ------------------------------------------------------------------------
  products: '/products',
  product: (id: string) => `/products/${id}`,
  productPattern: '/products/:productId',

  search: '/search',

  /** By slug, not by display name — a bookmarked section survives a rename. */
  category: (slug: string) => `/categories/${encodeURIComponent(slug)}`,
  categoryPattern: '/categories/:categoryName',
  categories: '/categories',

  cart: '/cart',
  checkout: '/checkout',

  orderSuccess: (id: string) => `/orders/${id}/success`,
  orderSuccessPattern: '/orders/:orderId/success',

  orders: '/orders',
  order: (id: string) => `/orders/${id}`,
  orderPattern: '/orders/:orderId',

  wishlist: '/wishlist',
  notifications: '/notifications',

  profile: '/account/profile',
  addresses: '/account/addresses',

  // ---- auth ----------------------------------------------------------------------------
  login: '/login',
  register: '/register',
  forgotPassword: '/forgot-password',
  resetPassword: '/reset-password',
  verifyEmail: '/verify-email',

  // ---- legal ---------------------------------------------------------------------------
  //
  // Public, and reachable without an account: consumer law calls these pre-contract
  // information, which means a shopper has to be able to read them before deciding to buy —
  // not after signing in.
  terms: '/terms',
  privacy: '/privacy',
  returns: '/returns',
  contact: '/contact',

  // ---- admin ---------------------------------------------------------------------------
  admin: {
    root: '/admin',
    dashboard: '/admin',
    products: '/admin/products',
    inventory: '/admin/inventory',
    warehouses: '/admin/warehouses',
    categories: '/admin/categories',
    orders: '/admin/orders',
    order: (id: string) => `/admin/orders/${id}`,
    orderPattern: '/admin/orders/:orderId',
    payments: '/admin/payments',
    reports: '/admin/reports',
    users: '/admin/users',
    coupons: '/admin/coupons',
    reviews: '/admin/reviews',
    shipments: '/admin/shipments',
    returns: '/admin/returns',
    audit: '/admin/audit',
  },

  notFound: '*',
} as const;

/**
 * Builds a login URL that returns the user where they were going.
 *
 * The guard uses this so an expired session on `/checkout` sends the user back to `/checkout`
 * after signing in, rather than dumping them on the homepage with a half-filled basket.
 */
export const loginWithRedirect = (from: string): string =>
  `${paths.login}?redirect=${encodeURIComponent(from)}`;

/** A backslash, as a code point — written this way so no escaping can mangle it. */
const BACKSLASH = String.fromCharCode(92);

/**
 * Validates a redirect target taken from the URL.
 *
 * A `?redirect=` parameter is attacker-controlled: anyone can send a victim a link to our own
 * login page that bounces them somewhere else once they authenticate. That is a textbook open
 * redirect, and it is what phishing flows are built on — the victim sees a genuine, trusted
 * login domain right up until the moment they are handed off.
 *
 * So the value is never trusted. Only a same-origin, path-relative target is accepted:
 *
 *   /checkout            accepted
 *   /orders?page=2       accepted
 *   //evil.com           rejected — protocol-relative, the browser treats it as absolute
 *   /(backslash)evil.com rejected — browsers normalise it into the case above
 *   https://evil.com     rejected — absolute
 *   javascript:alert(1)  rejected — scheme
 *
 * A rejected value falls back to the caller's default rather than failing the sign-in: the user
 * still gets in, just to a safe destination.
 *
 * This also closes our exposure to the React Router open-redirect advisory, independently of the
 * library version — trusting a URL from a query string is our bug to fix, not only theirs.
 */
export function safeRedirect(value: string | null | undefined): string | undefined {
  if (!value) return undefined;

  let candidate = value;

  // Decode first: an encoded `%2F%2Fevil.com` would otherwise slip past the checks below.
  try {
    candidate = decodeURIComponent(candidate);
  } catch {
    // Malformed percent-encoding is not something a legitimate redirect ever contains.
    return undefined;
  }

  candidate = candidate.trim();

  // Backslashes are the documented bypass: browsers normalise them to forward slashes, so a
  // value like `/\evil.com` becomes `//evil.com` — protocol-relative, and therefore external.
  if (candidate.includes(BACKSLASH)) return undefined;

  // Must be a rooted path, and must not be protocol-relative.
  if (!candidate.startsWith('/')) return undefined;
  if (candidate.startsWith('//')) return undefined;

  // Control characters can be used to smuggle a scheme past a naive check.
  for (let index = 0; index < candidate.length; index += 1) {
    const code = candidate.charCodeAt(index);
    if (code < 0x20 || code === 0x7f) return undefined;
  }

  return candidate;
}
