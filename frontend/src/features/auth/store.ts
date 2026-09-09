import { create } from 'zustand';

import { setSessionExpiredHandler } from '@/services/api-client';
import { tokenStorage, toStoredSession } from '@/services/token-storage';

import { authApi } from './api';
import { isAdmin, type AuthTokens, type Role, type User } from './types';

interface AuthState {
  user: User | null;
  /** True until the initial `/me` check finishes — routes must not decide anything before that. */
  isInitializing: boolean;
  isAuthenticated: boolean;

  /** Called after a successful login. Persists the tokens and the user. */
  setSession: (tokens: AuthTokens) => void;
  setUser: (user: User) => void;

  /** Restores the session on boot by validating the stored token against `/me`. */
  initialize: () => Promise<void>;

  logout: (options?: { notifyServer?: boolean }) => Promise<void>;

  /** Local-only teardown, for when the server has already invalidated the session. */
  clearSession: () => void;

  hasRole: (role: Role) => boolean;
  isAdmin: () => boolean;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  isInitializing: true,
  isAuthenticated: false,

  setSession: (tokens) => {
    tokenStorage.set(toStoredSession(tokens));
    set({ user: tokens.user, isAuthenticated: true, isInitializing: false });
  },

  setUser: (user) => set({ user, isAuthenticated: true }),

  /**
   * Boot-time session restore.
   *
   * The user object is *not* persisted alongside the tokens on purpose. Roles and account status
   * can change server-side, and a stale `roles: ['ADMIN']` in localStorage would let a demoted
   * account render the admin shell until its first API call failed. Asking `/me` costs one
   * request and makes the client's view of the session authoritative.
   */
  initialize: async () => {
    if (!tokenStorage.getAccessToken() && !tokenStorage.getRefreshToken()) {
      set({ isInitializing: false, isAuthenticated: false, user: null });
      return;
    }

    try {
      // A 401 here is handled by the api-client: it refreshes and replays transparently.
      const user = await authApi.me();
      set({ user, isAuthenticated: true, isInitializing: false });
    } catch {
      tokenStorage.clear();
      set({ user: null, isAuthenticated: false, isInitializing: false });
    }
  },

  logout: async ({ notifyServer = true } = {}) => {
    if (notifyServer && tokenStorage.getAccessToken()) {
      try {
        // Best effort: this denies the access token and revokes every refresh token server-side.
        // If it fails the local session still goes — never leave a user apparently signed in.
        await authApi.logout();
      } catch {
        /* deliberately ignored */
      }
    }
    get().clearSession();
  },

  clearSession: () => {
    tokenStorage.clear();
    set({ user: null, isAuthenticated: false, isInitializing: false });
  },

  hasRole: (role) => Boolean(get().user?.roles.includes(role)),

  isAdmin: () => isAdmin(get().user),
}));

/**
 * Bridges the api-client back to the store.
 *
 * The client cannot import the store (the store imports the client), so it exposes a hook the
 * store fills in at module load. When a refresh finally fails, this is what tears the session down.
 */
setSessionExpiredHandler(() => {
  useAuthStore.getState().clearSession();
});

/** Selectors — components subscribe to one slice so an unrelated change does not re-render them. */
export const selectUser = (state: AuthState) => state.user;
export const selectIsAuthenticated = (state: AuthState) => state.isAuthenticated;
export const selectIsInitializing = (state: AuthState) => state.isInitializing;
