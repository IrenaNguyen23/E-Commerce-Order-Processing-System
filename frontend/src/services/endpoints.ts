/**
 * Every URL the app can call, in one place.
 *
 * Feature api modules reference these instead of writing path strings, so a backend route change
 * is a one-line edit and a typo is a compile error rather than a 404 at runtime.
 *
 * Mirrors the controllers under each service's controller package. Nothing here is aspirational:
 * if a path is listed, the backend serves it.
 */
export const endpoints = {
  auth: {
    register: '/api/auth/register',
    login: '/api/auth/login',
    refresh: '/api/auth/refresh',
    logout: '/api/auth/logout',
    me: '/api/auth/me',
    forgotPassword: '/api/auth/forgot-password',
    resetPassword: '/api/auth/reset-password',
    verifyEmail: '/api/auth/verify-email',
    guest: '/api/auth/guest',
    resendVerification: '/api/auth/resend-verification',
  },

  users: {
    list: '/api/users',
    byId: (id: string) => `/api/users/${id}`,
  },

  /** The signed-in customer's own address book. Never takes a user id — the token supplies it. */
  addresses: {
    list: '/api/users/me/addresses',
    create: '/api/users/me/addresses',
    byId: (id: string) => `/api/users/me/addresses/${id}`,
    makeDefault: (id: string) => `/api/users/me/addresses/${id}/default`,
  },

  products: {
    list: '/api/products',
    create: '/api/products',
    update: (id: string) => `/api/products/${id}`,
    lookup: '/api/products/lookup',
    byId: (id: string) => `/api/products/${id}`,
    bySku: (sku: string) => `/api/products/sku/${encodeURIComponent(sku)}`,
    stock: (id: string) => `/api/products/${id}/stock`,
  },

  orders: {
    list: '/api/orders',
    create: '/api/orders',
    byId: (id: string) => `/api/orders/${id}`,
    byNumber: (orderNumber: string) => `/api/orders/number/${encodeURIComponent(orderNumber)}`,
    cancel: (id: string) => `/api/orders/${id}/cancel`,
  },

  shipping: {
    quotes: '/api/shipping/quotes',
  },

  /** The signed-in customer's stored basket. Never takes a user id — the token supplies it. */
  cart: {
    get: '/api/cart',
    items: '/api/cart/items',
    item: (productId: string) => `/api/cart/items/${productId}`,
    merge: '/api/cart/merge',
  },

  wishlist: {
    list: '/api/wishlist',
    item: (productId: string) => `/api/wishlist/${productId}`,
    moveToCart: (productId: string) => `/api/wishlist/${productId}/move-to-cart`,
  },

  shipments: {
    forOrder: (orderId: string) => `/api/orders/${orderId}/shipments`,
    track: (trackingNumber: string) =>
      `/api/shipments/track/${encodeURIComponent(trackingNumber)}`,
    queue: '/api/shipments',
    events: (shipmentId: string) => `/api/shipments/${shipmentId}/events`,
  },

  reviews: {
    forProduct: (productId: string) => `/api/products/${productId}/reviews`,
    mine: '/api/reviews/mine',
    pending: '/api/reviews/pending',
    moderate: (reviewId: string) => `/api/reviews/${reviewId}/moderate`,
    byId: (reviewId: string) => `/api/reviews/${reviewId}`,
  },

  categories: {
    tree: '/api/categories',
    flat: '/api/categories/flat',
    bySlug: (slug: string) => `/api/categories/${encodeURIComponent(slug)}`,
    byId: (id: string) => `/api/categories/${id}`,
  },

  warehouses: {
    list: '/api/warehouses',
    byId: (id: string) => `/api/warehouses/${id}`,
    stock: (id: string) => `/api/warehouses/${id}/stock`,
    setStock: (id: string, productId: string) => `/api/warehouses/${id}/stock/${productId}`,
    locate: (productId: string) => `/api/warehouses/stock/${productId}`,
    lowStock: '/api/warehouses/low-stock',
  },

  productImages: {
    list: (productId: string) => `/api/products/${productId}/images`,
    upload: (productId: string) => `/api/products/${productId}/images`,
    primary: (productId: string, imageId: string) =>
      `/api/products/${productId}/images/${imageId}/primary`,
    byId: (productId: string, imageId: string) =>
      `/api/products/${productId}/images/${imageId}`,
  },

  coupons: {
    preview: '/api/coupons/preview',
    list: '/api/coupons',
    byId: (id: string) => `/api/coupons/${id}`,
    redemptions: (id: string) => `/api/coupons/${id}/redemptions`,
  },

  payments: {
    process: '/api/payments/process',
    byId: (id: string) => `/api/payments/${id}`,
    byOrderId: (orderId: string) => `/api/payments/order/${orderId}`,
  },

  notifications: {
    list: '/api/notifications',
    byId: (id: string) => `/api/notifications/${id}`,
  },

  /**
   * The operator audit trail — one path per service, on purpose.
   *
   * Each service keeps its own log in its own database, so there is no single URL to read. The
   * gateway routes `/api/audit/{service}/...` to that service's `/api/audit/...`, and the back
   * office asks all five and merges the answers.
   */
  audit: {
    search: (service: string) => `/api/audit/${service}`,
    actions: (service: string) => `/api/audit/${service}/actions`,
  },

  /**
   * Returns. Order Service owns them, because a return refers to an order and is priced from
   * the line totals frozen on it.
   */
  returns: {
    returnable: (orderId: string) => `/api/orders/${orderId}/returnable`,
    forOrder: (orderId: string) => `/api/orders/${orderId}/returns`,
    mine: '/api/returns/mine',
    byId: (id: string) => `/api/returns/${id}`,
    cancel: (id: string) => `/api/returns/${id}/cancel`,
    queue: '/api/returns',
    approve: (id: string) => `/api/returns/${id}/approve`,
    reject: (id: string) => `/api/returns/${id}/reject`,
    received: (id: string) => `/api/returns/${id}/received`,
    refund: (id: string) => `/api/returns/${id}/refund`,
  },
} as const;
