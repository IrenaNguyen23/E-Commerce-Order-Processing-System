import axios from 'axios';

import { ErrorCode, type ApiErrorResponse, type AppError, type FieldError } from '@/types/api';

/**
 * Human wording for the error codes a user can actually trigger.
 *
 * The backend message is usually good enough, but for a handful of codes a UI-specific phrasing
 * is clearer — or, in the case of credentials, deliberately vaguer than the technical truth.
 */
const MESSAGES: Record<string, string> = {
  [ErrorCode.INVALID_CREDENTIALS]: 'Email or password is incorrect.',
  [ErrorCode.TOKEN_EXPIRED]: 'Your session has expired. Please sign in again.',
  [ErrorCode.TOKEN_INVALID]: 'Your session is no longer valid. Please sign in again.',
  [ErrorCode.REFRESH_TOKEN_INVALID]:
    'Your session was ended for security reasons. Please sign in again.',
  [ErrorCode.UNAUTHORIZED]: 'Please sign in to continue.',
  [ErrorCode.FORBIDDEN]: 'You do not have permission to do that.',
  [ErrorCode.ACCOUNT_DISABLED]: 'This account has been disabled. Contact support for help.',
  [ErrorCode.EMAIL_ALREADY_REGISTERED]: 'An account already exists for that email address.',
  [ErrorCode.INSUFFICIENT_STOCK]: 'There is not enough stock for one of the items in your basket.',
  [ErrorCode.PRODUCT_NOT_FOUND]: 'That product is no longer available.',
  [ErrorCode.ORDER_NOT_FOUND]: 'That order could not be found.',
  [ErrorCode.EMPTY_ORDER]: 'Your basket is empty.',
  [ErrorCode.PAYMENT_DECLINED]: 'The payment was declined. Please try a different payment method.',
  [ErrorCode.PAYMENT_ALREADY_PROCESSED]: 'This order has already been paid.',
  [ErrorCode.RATE_LIMITED]: 'Too many requests. Please wait a moment and try again.',
  [ErrorCode.SERVICE_UNAVAILABLE]:
    'That service is temporarily unavailable. Please try again shortly.',
  [ErrorCode.INTERNAL_ERROR]: 'Something went wrong on our side. Please try again.',
  [ErrorCode.NETWORK_ERROR]: 'Cannot reach the server. Check your connection and try again.',
  [ErrorCode.TIMEOUT]: 'The request took too long. Please try again.',
};

/** A response body is only trusted as an error envelope if it has the shape we expect. */
const isApiErrorResponse = (value: unknown): value is ApiErrorResponse =>
  typeof value === 'object' &&
  value !== null &&
  'code' in value &&
  typeof (value as ApiErrorResponse).code === 'string';

/**
 * Retrying is safe only when the failure is transient *and* the request is idempotent.
 *
 * `POST /api/orders` is deliberately excluded at the call site rather than here — placing an
 * order twice is far worse than showing an error once, and only the caller knows the method.
 */
const isRetryableStatus = (status: number): boolean =>
  status === 0 || status === 408 || status === 429 || status === 502 || status === 503 || status === 504;

/**
 * Collapses anything thrown by axios into a single {@link AppError}.
 *
 * Every layer above this — hooks, components, the toast handler — deals with one shape, so there
 * is no `error?.response?.data?.message ?? error.message` scattered through the app.
 */
export function normalizeError(error: unknown): AppError {
  if (isAppError(error)) {
    return error;
  }

  if (axios.isAxiosError(error)) {
    if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
      return {
        code: ErrorCode.TIMEOUT,
        message: MESSAGES[ErrorCode.TIMEOUT]!,
        status: 0,
        retryable: true,
      };
    }

    // No response at all: DNS, offline, connection refused, CORS.
    if (!error.response) {
      return {
        code: ErrorCode.NETWORK_ERROR,
        message: MESSAGES[ErrorCode.NETWORK_ERROR]!,
        status: 0,
        retryable: true,
      };
    }

    const { status, data } = error.response;

    if (isApiErrorResponse(data)) {
      return {
        code: data.code,
        message: MESSAGES[data.code] ?? data.message ?? 'Request failed.',
        status: data.status || status,
        fieldErrors: data.fieldErrors ?? undefined,
        correlationId: data.correlationId,
        retryable: isRetryableStatus(status),
      };
    }

    // A response the gateway or a proxy produced, not the application.
    return {
      code: status >= 500 ? ErrorCode.INTERNAL_ERROR : ErrorCode.UNKNOWN,
      message:
        status >= 500
          ? MESSAGES[ErrorCode.INTERNAL_ERROR]!
          : error.message || 'Request failed.',
      status,
      retryable: isRetryableStatus(status),
    };
  }

  if (error instanceof Error) {
    return { code: ErrorCode.UNKNOWN, message: error.message, status: 0, retryable: false };
  }

  return {
    code: ErrorCode.UNKNOWN,
    message: 'An unexpected error occurred.',
    status: 0,
    retryable: false,
  };
}

export function isAppError(value: unknown): value is AppError {
  return (
    typeof value === 'object' &&
    value !== null &&
    'code' in value &&
    'message' in value &&
    'retryable' in value
  );
}

/** True when the failure means the session is gone and the user must sign in again. */
export function isAuthError(error: AppError): boolean {
  return (
    error.status === 401 ||
    error.code === ErrorCode.UNAUTHORIZED ||
    error.code === ErrorCode.TOKEN_EXPIRED ||
    error.code === ErrorCode.TOKEN_INVALID ||
    error.code === ErrorCode.REFRESH_TOKEN_INVALID
  );
}

/** Field errors keyed by field name, ready to feed to React Hook Form's `setError`. */
export function toFieldErrorMap(error: AppError): Record<string, string> {
  const map: Record<string, string> = {};
  error.fieldErrors?.forEach((fieldError: FieldError) => {
    if (fieldError.field && !map[fieldError.field]) {
      map[fieldError.field] = fieldError.message;
    }
  });
  return map;
}
