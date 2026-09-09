/**
 * The wire contract, mirrored from `docs/api/commerceflow-openapi.yaml`.
 *
 * These envelope types exist only inside `services/` — the response interceptor unwraps them, so
 * a feature's api module returns `Product`, never `ApiResponse<Product>`.
 */

/** Uniform success envelope returned by every 2xx response. */
export interface ApiResponse<T> {
  success: true;
  data: T;
  message: string | null;
  timestamp: string;
  correlationId: string | null;
}

/** A single failed bean-validation constraint. */
export interface FieldError {
  field: string;
  rejectedValue: unknown;
  message: string;
}

/** Uniform error envelope returned by every non-2xx response. */
export interface ApiErrorResponse {
  success: false;
  timestamp: string;
  status: number;
  error: string;
  code: string;
  message: string;
  path: string;
  correlationId: string | null;
  fieldErrors?: FieldError[] | null;
}

/** Transport-friendly page envelope. Note `page` is zero-based. */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

/** Query parameters every paged endpoint accepts. */
export interface PageParams {
  page?: number;
  size?: number;
  sortBy?: string;
  direction?: 'asc' | 'desc';
}

/**
 * Every error in the app is normalised to this shape, whatever its origin — a backend
 * `ApiErrorResponse`, an axios network failure, or a timeout. One shape means one set of
 * handling rules.
 */
export interface AppError {
  /** Machine-readable code. `NETWORK_ERROR` / `TIMEOUT` for failures that never reached the API. */
  code: string;
  /** Message safe to show a user. */
  message: string;
  /** HTTP status, or 0 when the request never got a response. */
  status: number;
  /** Present when the backend rejected specific fields; mapped onto form fields. */
  fieldErrors?: FieldError[];
  /** Quote this in a support request — it stitches the saga together in the backend logs. */
  correlationId?: string | null;
  /** Set when a retry could plausibly succeed. */
  retryable: boolean;
}

/** Error codes the UI branches on. The backend defines 28; these are the ones we react to. */
export const ErrorCode = {
  VALIDATION_FAILED: 'VALIDATION_FAILED',
  UNAUTHORIZED: 'UNAUTHORIZED',
  INVALID_CREDENTIALS: 'INVALID_CREDENTIALS',
  TOKEN_EXPIRED: 'TOKEN_EXPIRED',
  TOKEN_INVALID: 'TOKEN_INVALID',
  FORBIDDEN: 'FORBIDDEN',
  RESOURCE_NOT_FOUND: 'RESOURCE_NOT_FOUND',
  CONFLICT: 'CONFLICT',
  RATE_LIMITED: 'RATE_LIMITED',
  INTERNAL_ERROR: 'INTERNAL_ERROR',
  SERVICE_UNAVAILABLE: 'SERVICE_UNAVAILABLE',

  EMAIL_ALREADY_REGISTERED: 'EMAIL_ALREADY_REGISTERED',
  ACCOUNT_DISABLED: 'ACCOUNT_DISABLED',
  REFRESH_TOKEN_INVALID: 'REFRESH_TOKEN_INVALID',

  PRODUCT_NOT_FOUND: 'PRODUCT_NOT_FOUND',
  SKU_ALREADY_EXISTS: 'SKU_ALREADY_EXISTS',
  INSUFFICIENT_STOCK: 'INSUFFICIENT_STOCK',

  ORDER_NOT_FOUND: 'ORDER_NOT_FOUND',
  EMPTY_ORDER: 'EMPTY_ORDER',

  PAYMENT_NOT_FOUND: 'PAYMENT_NOT_FOUND',
  PAYMENT_ALREADY_PROCESSED: 'PAYMENT_ALREADY_PROCESSED',
  PAYMENT_DECLINED: 'PAYMENT_DECLINED',

  /** Client-side only: the request never reached the gateway. */
  NETWORK_ERROR: 'NETWORK_ERROR',
  TIMEOUT: 'TIMEOUT',
  UNKNOWN: 'UNKNOWN',
} as const;

export type ErrorCodeValue = (typeof ErrorCode)[keyof typeof ErrorCode];
