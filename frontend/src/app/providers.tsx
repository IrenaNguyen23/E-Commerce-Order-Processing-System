import { QueryClientProvider } from '@tanstack/react-query';
import { useEffect, type ReactNode } from 'react';
import { BrowserRouter } from 'react-router-dom';
import { Toaster } from 'sonner';

import { useAuthStore } from '@/features/auth/store';

import { ErrorBoundary } from './error-boundary';
import { queryClient } from './query-client';

/**
 * Restores the session before the router renders anything.
 *
 * Route guards read `isInitializing` and hold, so this runs exactly once and every guard makes
 * its decision against a validated session rather than a stale token.
 */
function SessionBootstrap({ children }: { children: ReactNode }) {
  const initialize = useAuthStore((state) => state.initialize);

  useEffect(() => {
    void initialize();
  }, [initialize]);

  return <>{children}</>;
}

/**
 * Provider stack, outermost first.
 *
 * The order is deliberate: the error boundary wraps everything so a crash inside a provider is
 * still caught; the router sits inside Query so hooks can navigate on a mutation result.
 */
export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <SessionBootstrap>
            {children}

            <Toaster
              position="top-right"
              richColors
              closeButton
              // Long enough to read an error with a correlation id, short enough not to nag.
              duration={5000}
              toastOptions={{ className: 'text-sm' }}
            />
          </SessionBootstrap>
        </BrowserRouter>
      </QueryClientProvider>
    </ErrorBoundary>
  );
}
