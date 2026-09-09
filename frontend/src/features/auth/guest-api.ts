import { useMutation } from '@tanstack/react-query';
import { toast } from 'sonner';

import { api } from '@/services/api-client';
import { endpoints } from '@/services/endpoints';
import { normalizeError } from '@/services/error';

import { useAuthStore } from './store';
import type { AuthTokens } from './types';

/**
 * Checking out without an account.
 *
 * <h2>A guest is signed in; they just never chose a password</h2>
 *
 * The server creates a real account and returns a real session, so everything after this point —
 * the basket, the order, the payment, the order history — is the ordinary signed-in path. There is
 * no second flow to keep working.
 *
 * If the customer later wants an account, they set a password through the ordinary "forgot
 * password" flow and find their orders already there. That is worth saying on the confirmation
 * page, because it is a genuinely good deal and nobody would guess it.
 *
 * <h2>An address that already has an account is refused</h2>
 *
 * By design: this endpoint hands out a session in exchange for an email address, so reusing an
 * existing account would let anybody type your address and be signed in as you. The refusal is
 * turned into a prompt to sign in, which is what the customer wanted anyway.
 */

export interface GuestSessionPayload {
  email: string;
  fullName?: string;
}

export function useStartGuestSession() {
  const setSession = useAuthStore((state) => state.setSession);

  return useMutation({
    mutationFn: (payload: GuestSessionPayload) =>
      api.post<AuthTokens>(endpoints.auth.guest, payload),

    onSuccess: (tokens) => {
      // Straight into the normal session. Nothing downstream can tell the difference, which is
      // the whole point of doing it this way.
      setSession(tokens);
    },

    onError: (error) => {
      const normalised = normalizeError(error);
      // 409 means the address has an account. Not really an error from the customer's side —
      // they have an account and should use it — so the message says that rather than "conflict".
      toast.error(normalised.message);
    },
  });
}
