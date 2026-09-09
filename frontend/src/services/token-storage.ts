import { storageKeys } from '@/shared/config';

/**
 * Persisted half of the session.
 *
 * `localStorage` is a deliberate trade-off. The ideal is an httpOnly cookie, but the backend
 * issues bearer tokens (ADR-001) and the gateway expects an `Authorization` header, so the token
 * has to be readable by JavaScript regardless. Given that, what actually reduces risk is keeping
 * the access token short-lived (15 min) and the refresh token single-use with reuse detection —
 * both of which the backend already does.
 *
 * Kept behind this module so swapping the strategy later touches exactly one file.
 */

export interface StoredSession {
  accessToken: string;
  refreshToken: string;
  /** Epoch milliseconds at which the access token expires. */
  expiresAt: number;
}

const isValid = (value: unknown): value is StoredSession =>
  typeof value === 'object' &&
  value !== null &&
  typeof (value as StoredSession).accessToken === 'string' &&
  typeof (value as StoredSession).refreshToken === 'string' &&
  typeof (value as StoredSession).expiresAt === 'number';

/**
 * In-memory mirror of the persisted session.
 *
 * The request interceptor runs on every call, and reading `localStorage` synchronously that often
 * is both slow and a source of stale reads across tabs. The mirror is the source of truth for
 * reads; `localStorage` is the durability layer.
 */
let cached: StoredSession | null = null;
let loaded = false;

export const tokenStorage = {
  get(): StoredSession | null {
    if (loaded) return cached;
    loaded = true;

    try {
      const raw = localStorage.getItem(storageKeys.auth);
      if (!raw) {
        cached = null;
        return null;
      }
      const parsed: unknown = JSON.parse(raw);
      cached = isValid(parsed) ? parsed : null;
    } catch {
      // Corrupt or unavailable (private mode, storage disabled): behave as signed out rather
      // than crashing the app on boot.
      cached = null;
    }
    return cached;
  },

  set(session: StoredSession): void {
    cached = session;
    loaded = true;
    try {
      localStorage.setItem(storageKeys.auth, JSON.stringify(session));
    } catch {
      // Storage full or blocked. The in-memory session still works for this tab.
    }
  },

  clear(): void {
    cached = null;
    loaded = true;
    try {
      localStorage.removeItem(storageKeys.auth);
    } catch {
      /* nothing meaningful to do */
    }
  },

  getAccessToken(): string | null {
    return this.get()?.accessToken ?? null;
  },

  getRefreshToken(): string | null {
    return this.get()?.refreshToken ?? null;
  },

  /**
   * True when the access token is expired or about to be.
   *
   * The 30 s skew matches the backend's `clock-skew` setting and stops a token expiring
   * mid-flight between the interceptor reading it and the gateway validating it.
   */
  isExpired(skewMs = 30_000): boolean {
    const session = this.get();
    if (!session) return true;
    return Date.now() >= session.expiresAt - skewMs;
  },
};

/** Builds a session from a login/refresh response. `expiresIn` is seconds, per the contract. */
export function toStoredSession(tokens: {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
}): StoredSession {
  return {
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
    expiresAt: Date.now() + tokens.expiresIn * 1000,
  };
}
