import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { paths } from '@/routes/paths';
import { normalizeError, toFieldErrorMap } from '@/services/error';
import type { AppError } from '@/types/api';

import { authApi } from './api';
import { useAuthStore } from './store';
import type { LoginPayload, RegisterPayload } from './types';

/**
 * Login.
 *
 * On success the whole query cache is cleared: the previous visitor's orders and notifications
 * must not survive into the next session, even for the instant before a refetch replaces them.
 */
export function useLogin(redirectTo?: string) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const setSession = useAuthStore((state) => state.setSession);

  return useMutation({
    mutationFn: (payload: LoginPayload) => authApi.login(payload),
    onSuccess: (tokens) => {
      queryClient.clear();
      setSession(tokens);
      toast.success(`Welcome back, ${tokens.user.fullName.split(' ')[0]}`);

      const isAdminUser = tokens.user.roles.includes('ADMIN');
      navigate(redirectTo ?? (isAdminUser ? paths.admin.dashboard : paths.home), {
        replace: true,
      });
    },
  });
}

/** Registration. Logs the user straight in afterwards — a second form would be friction. */
export function useRegister() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const setSession = useAuthStore((state) => state.setSession);

  return useMutation({
    mutationFn: async (payload: RegisterPayload) => {
      await authApi.register(payload);
      return authApi.login({ email: payload.email, password: payload.password });
    },
    onSuccess: (tokens) => {
      queryClient.clear();
      setSession(tokens);
      toast.success('Your account is ready');
      navigate(paths.home, { replace: true });
    },
  });
}

/**
 * Requests a password reset link.
 *
 * Always reports success, because the backend always reports success. The message deliberately
 * says *if an account exists* rather than *we sent you an email* — claiming an email was sent
 * would both lie to someone who mistyped their address and confirm to a stranger that an address
 * is registered.
 */
export function useForgotPassword() {
  return useMutation({
    mutationFn: (email: string) => authApi.forgotPassword(email),
  });
}

/**
 * Completes a reset and sends the customer to sign in again.
 *
 * They have to: a completed reset revokes every session, including any this browser held.
 */
export function useResetPassword() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const clearSession = useAuthStore((state) => state.clearSession);

  return useMutation({
    mutationFn: ({ token, password }: { token: string; password: string }) =>
      authApi.resetPassword(token, password),
    onSuccess: () => {
      // Any tokens this browser held were revoked server-side a moment ago. Keeping them would
      // mean the next request fails with a 401 the user cannot explain.
      clearSession();
      queryClient.clear();
      toast.success('Password changed. Please sign in.');
      navigate(paths.login, { replace: true });
    },
  });
}

export function useVerifyEmail() {
  return useMutation({
    mutationFn: (token: string) => authApi.verifyEmail(token),
  });
}

export function useResendVerification() {
  return useMutation({
    mutationFn: (email: string) => authApi.resendVerification(email),
    onSuccess: () => toast.success('If that address needs confirming, a new link is on its way.'),
  });
}

export function useLogout() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const logout = useAuthStore((state) => state.logout);

  return useMutation({
    mutationFn: () => logout(),
    onSuccess: () => {
      queryClient.clear();
      toast.success('Signed out');
      navigate(paths.home, { replace: true });
    },
  });
}

/**
 * Maps a failed mutation onto a form.
 *
 * Field-level errors from the backend land on the matching input; anything else becomes a toast.
 * Without this, a 409 on a duplicate email would surface as a generic banner instead of on the
 * email field where the user is looking.
 */
export function applyServerErrors(
  error: unknown,
  setError: (field: string, error: { type: string; message: string }) => void,
  knownFields: string[],
): AppError {
  const appError = normalizeError(error);
  const fieldErrors = toFieldErrorMap(appError);

  let matchedAny = false;
  Object.entries(fieldErrors).forEach(([field, message]) => {
    if (knownFields.includes(field)) {
      setError(field, { type: 'server', message });
      matchedAny = true;
    }
  });

  // Duplicate email comes back as a 409 with no fieldErrors — put it where it belongs anyway.
  if (!matchedAny && appError.code === 'EMAIL_ALREADY_REGISTERED' && knownFields.includes('email')) {
    setError('email', { type: 'server', message: appError.message });
    matchedAny = true;
  }

  if (!matchedAny) {
    toast.error(appError.message);
  }

  return appError;
}
