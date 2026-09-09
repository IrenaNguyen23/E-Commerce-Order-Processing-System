import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';

import { FullPageLoader } from '@/components/common/states';
import { useAuthStore } from '@/features/auth/store';

import { loginWithRedirect, paths } from './paths';

/**
 * Requires a signed-in user.
 *
 * The `isInitializing` check is the important part: on a hard refresh the store has tokens but
 * has not yet validated them against `/me`. Deciding before that resolves would bounce an
 * authenticated user to the login screen on every reload.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const isInitializing = useAuthStore((state) => state.isInitializing);
  const location = useLocation();

  if (isInitializing) {
    return <FullPageLoader label="Checking your session…" />;
  }

  if (!isAuthenticated) {
    // `replace` so the protected URL does not sit in history behind the login page.
    return <Navigate to={loginWithRedirect(location.pathname + location.search)} replace />;
  }

  return <>{children}</>;
}

/**
 * Requires the ADMIN role.
 *
 * This is a UX guard, not a security boundary — every admin endpoint is `@PreAuthorize`-checked
 * server-side. Its job is to avoid rendering a console the user cannot use, not to protect data.
 */
export function RequireAdmin({ children }: { children: ReactNode }) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const isInitializing = useAuthStore((state) => state.isInitializing);
  const isAdmin = useAuthStore((state) => state.isAdmin());
  const location = useLocation();

  if (isInitializing) {
    return <FullPageLoader label="Checking your session…" />;
  }

  if (!isAuthenticated) {
    return <Navigate to={loginWithRedirect(location.pathname + location.search)} replace />;
  }

  if (!isAdmin) {
    return <Navigate to={paths.home} replace />;
  }

  return <>{children}</>;
}

/**
 * Keeps a signed-in user off the login and register screens.
 *
 * Landing on a login form while already authenticated is disorienting, and submitting it would
 * rotate a perfectly good session for no reason.
 */
export function RedirectIfAuthenticated({ children }: { children: ReactNode }) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const isInitializing = useAuthStore((state) => state.isInitializing);

  if (isInitializing) {
    return <FullPageLoader />;
  }

  if (isAuthenticated) {
    return <Navigate to={paths.home} replace />;
  }

  return <>{children}</>;
}
