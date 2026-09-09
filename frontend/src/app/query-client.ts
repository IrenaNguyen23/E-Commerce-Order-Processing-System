import { QueryClient } from '@tanstack/react-query';

import { isAppError, isAuthError } from '@/services/error';

/**
 * The one QueryClient for the app.
 *
 * The defaults matter more than they look — they decide how the whole app behaves under a flaky
 * network, so they are set once here rather than argued about per query.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      /**
       * The axios client already retries transient failures with backoff. Retrying again here
       * would multiply them (3 × 3 = 9 requests for one failed load), so Query's own retry is
       * reserved for the case axios cannot see: a genuine 5xx that came back fast.
       */
      retry: (failureCount, error) => {
        if (isAppError(error)) {
          // A 401 is the api-client's business — it refreshes and replays. Retrying here would
          // race that and could burn the single-use refresh token.
          if (isAuthError(error)) return false;
          // A 404 or a validation failure will never succeed on retry.
          if (error.status >= 400 && error.status < 500) return false;
        }
        return failureCount < 1;
      },

      retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 8000),

      /**
       * Refetch when the tab regains focus, but not on every remount. This is the balance that
       * matters for an order list: a customer returning to the tab should see the current state
       * of their saga, but navigating between two pages should not refire every query.
       */
      refetchOnWindowFocus: true,
      refetchOnMount: true,
      refetchOnReconnect: true,

      staleTime: 30_000,
      // Cache survives long enough for back-navigation to feel instant, then is collected.
      gcTime: 5 * 60_000,
    },

    mutations: {
      // Never retry a mutation automatically. Every mutation here either moves money or moves
      // stock; ambiguity is the user's to resolve, not the client's to paper over.
      retry: false,
    },
  },
});
