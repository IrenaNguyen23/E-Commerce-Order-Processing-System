import { api } from '@/services/api-client';
import { endpoints } from '@/services/endpoints';

import type { AuthTokens, LoginPayload, RegisterPayload, User } from './types';

/**
 * Auth Service client.
 *
 * `skipAuth` on register/login/refresh: attaching an expired bearer token to a login request is
 * pointless at best, and would trigger the 401 refresh path at worst.
 */
export const authApi = {
  register: (payload: RegisterPayload): Promise<User> =>
    api.post<User>(endpoints.auth.register, payload, { skipAuth: true }),

  login: (payload: LoginPayload): Promise<AuthTokens> =>
    api.post<AuthTokens>(endpoints.auth.login, payload, { skipAuth: true }),

  /** Rotates the pair. The api-client handles this automatically on 401; this is the manual path. */
  refresh: (refreshToken: string): Promise<AuthTokens> =>
    api.post<AuthTokens>(endpoints.auth.refresh, { refreshToken }, { skipAuth: true }),

  logout: (): Promise<void> => api.post<void>(endpoints.auth.logout),

  me: (): Promise<User> => api.get<User>(endpoints.auth.me),

  /**
   * Asks for a reset link.
   *
   * Resolves the same way for an address that has an account and one that does not — the backend
   * answers identically on purpose, so nobody can use this to check who is a customer. The UI
   * must not try to be more helpful than that.
   */
  forgotPassword: (email: string): Promise<void> =>
    api.post<void>(endpoints.auth.forgotPassword, { email }, { skipAuth: true }),

  /** Consumes the emailed token. Single use, and it signs the account out everywhere. */
  resetPassword: (token: string, newPassword: string): Promise<void> =>
    api.post<void>(endpoints.auth.resetPassword, { token, newPassword }, { skipAuth: true }),

  verifyEmail: (token: string): Promise<void> =>
    api.post<void>(endpoints.auth.verifyEmail, { token }, { skipAuth: true }),

  resendVerification: (email: string): Promise<void> =>
    api.post<void>(endpoints.auth.resendVerification, { email }, { skipAuth: true }),
};
