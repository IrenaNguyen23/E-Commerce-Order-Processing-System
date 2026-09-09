import axios, {
  AxiosError,
  type AxiosInstance,
  type AxiosRequestConfig,
  type InternalAxiosRequestConfig,
} from 'axios';

import { config } from '@/shared/config';
import type { ApiResponse } from '@/types/api';

import { isAuthError, normalizeError } from './error';
import { tokenStorage, toStoredSession } from './token-storage';

/** Extra flags we attach to a request config to drive the interceptors. */
interface RequestMeta {
  /** Set once a request has already been retried after a refresh, so it cannot loop. */
  _retriedAfterRefresh?: boolean;
  /** Attempts already made by the transient-failure retry. */
  _retryCount?: number;
  /** Opt out of the Authorization header (login, register, refresh). */
  skipAuth?: boolean;
  /** Opt out of transient retry. Set on anything non-idempotent — placing an order, above all. */
  skipRetry?: boolean;
}

type CFRequestConfig = InternalAxiosRequestConfig & RequestMeta;
export type ApiRequestConfig = AxiosRequestConfig & Pick<RequestMeta, 'skipAuth' | 'skipRetry'>;

const MAX_RETRIES = 2;
const RETRY_BASE_DELAY_MS = 400;

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

// ---------------------------------------------------------------------------------------------
// Session-expiry notification
// ---------------------------------------------------------------------------------------------

/**
 * Called when the session is unrecoverable. The auth store subscribes at boot.
 *
 * An indirection rather than a direct import because the store imports the API client — wiring
 * it the other way round would be a cycle.
 */
type SessionExpiredHandler = () => void;
let onSessionExpired: SessionExpiredHandler = () => {};

export function setSessionExpiredHandler(handler: SessionExpiredHandler): void {
  onSessionExpired = handler;
}

// ---------------------------------------------------------------------------------------------
// Instance
// ---------------------------------------------------------------------------------------------

export const apiClient: AxiosInstance = axios.create({
  baseURL: config.apiBaseUrl,
  timeout: config.apiTimeout,
  headers: { 'Content-Type': 'application/json' },
});

/**
 * A second, bare instance for the refresh call itself.
 *
 * It must not carry the response interceptor below, or a failing refresh would recurse into
 * refresh handling and deadlock against its own in-flight promise.
 */
const refreshClient: AxiosInstance = axios.create({
  baseURL: config.apiBaseUrl,
  timeout: config.apiTimeout,
  headers: { 'Content-Type': 'application/json' },
});

// ---------------------------------------------------------------------------------------------
// Request interceptor — auth + correlation
// ---------------------------------------------------------------------------------------------

apiClient.interceptors.request.use((request: CFRequestConfig) => {
  if (!request.skipAuth) {
    const token = tokenStorage.getAccessToken();
    if (token) {
      request.headers.set('Authorization', `Bearer ${token}`);
    }
  }

  // Let the caller correlate a browser action with the backend saga it triggers. The gateway
  // honours a client-supplied id and stamps one otherwise.
  if (!request.headers.has('X-Correlation-Id')) {
    request.headers.set('X-Correlation-Id', crypto.randomUUID());
  }

  return request;
});

// ---------------------------------------------------------------------------------------------
// Refresh coordination
// ---------------------------------------------------------------------------------------------

/**
 * The single in-flight refresh.
 *
 * This matters more here than in a typical app: the backend treats a refresh token as **single
 * use** and interprets reuse as a compromise, revoking every session of the account. If three
 * requests 401 at once and each calls `/refresh`, two of them present an already-rotated token
 * and the user is signed out everywhere.
 *
 * So: the first 401 starts a refresh, every other request awaits the same promise.
 */
let refreshInFlight: Promise<string | null> | null = null;

async function performRefresh(): Promise<string | null> {
  const refreshToken = tokenStorage.getRefreshToken();
  if (!refreshToken) return null;

  try {
    const response = await refreshClient.post<ApiResponse<{
      accessToken: string;
      refreshToken: string;
      expiresIn: number;
    }>>('/api/auth/refresh', { refreshToken });

    const tokens = response.data.data;
    tokenStorage.set(toStoredSession(tokens));
    return tokens.accessToken;
  } catch {
    // Rotated, expired or revoked — either way this session is over.
    tokenStorage.clear();
    return null;
  }
}

function refreshSession(): Promise<string | null> {
  refreshInFlight ??= performRefresh().finally(() => {
    refreshInFlight = null;
  });
  return refreshInFlight;
}

// ---------------------------------------------------------------------------------------------
// Response interceptor — unwrap, refresh, retry
// ---------------------------------------------------------------------------------------------

apiClient.interceptors.response.use(
  /**
   * Unwrap the success envelope exactly once, here.
   *
   * Every api module above therefore returns the domain type: `getProduct()` resolves to
   * `Product`, not `ApiResponse<Product>`.
   */
  (response) => {
    const body = response.data as unknown;
    if (body && typeof body === 'object' && 'success' in body && 'data' in body) {
      response.data = (body as ApiResponse<unknown>).data;
    }
    return response;
  },

  async (error: AxiosError) => {
    const request = error.config as CFRequestConfig | undefined;

    if (!request) {
      return Promise.reject(normalizeError(error));
    }

    const appError = normalizeError(error);

    // ---- 401: refresh once, then replay the original request ---------------------------
    if (
      error.response?.status === 401 &&
      !request.skipAuth &&
      !request._retriedAfterRefresh &&
      tokenStorage.getRefreshToken()
    ) {
      request._retriedAfterRefresh = true;

      const accessToken = await refreshSession();
      if (accessToken) {
        request.headers.set('Authorization', `Bearer ${accessToken}`);
        return apiClient.request(request);
      }

      // Refresh failed: the session is genuinely gone.
      onSessionExpired();
      return Promise.reject(appError);
    }

    // A 401 that we cannot recover from — no refresh token, or the replay also failed.
    if (isAuthError(appError) && !request.skipAuth) {
      onSessionExpired();
      return Promise.reject(appError);
    }

    // ---- transient failure: bounded retry with backoff ----------------------------------
    // Only for requests the caller has not marked unsafe. Mutations opt out at the call site,
    // because replaying a POST that may have succeeded is worse than surfacing the error.
    const attempts = request._retryCount ?? 0;
    if (appError.retryable && !request.skipRetry && attempts < MAX_RETRIES) {
      request._retryCount = attempts + 1;
      // Exponential, with jitter so a fleet of tabs does not retry in lockstep.
      const delay = RETRY_BASE_DELAY_MS * 2 ** attempts + Math.random() * 200;
      await sleep(delay);
      return apiClient.request(request);
    }

    return Promise.reject(appError);
  },
);

// ---------------------------------------------------------------------------------------------
// Typed helpers
// ---------------------------------------------------------------------------------------------

/**
 * Thin wrappers so feature code never touches `response.data` or axios generics.
 *
 * Mutations default to `skipRetry` — see the retry note above.
 */
export const api = {
  get: <T>(url: string, config?: ApiRequestConfig): Promise<T> =>
    apiClient.get<T, { data: T }>(url, config).then((response) => response.data),

  post: <T>(url: string, body?: unknown, config?: ApiRequestConfig): Promise<T> =>
    apiClient
      .post<T, { data: T }>(url, body, { skipRetry: true, ...config })
      .then((response) => response.data),

  put: <T>(url: string, body?: unknown, config?: ApiRequestConfig): Promise<T> =>
    apiClient
      .put<T, { data: T }>(url, body, { skipRetry: true, ...config })
      .then((response) => response.data),

  patch: <T>(url: string, body?: unknown, config?: ApiRequestConfig): Promise<T> =>
    apiClient
      .patch<T, { data: T }>(url, body, { skipRetry: true, ...config })
      .then((response) => response.data),

  delete: <T>(url: string, config?: ApiRequestConfig): Promise<T> =>
    apiClient
      .delete<T, { data: T }>(url, { skipRetry: true, ...config })
      .then((response) => response.data),
};
