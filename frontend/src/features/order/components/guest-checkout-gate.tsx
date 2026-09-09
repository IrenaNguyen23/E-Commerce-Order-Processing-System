import { UserPlus } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';

import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { useStartGuestSession } from '@/features/auth/guest-api';
import { paths } from '@/routes/paths';

/**
 * Checking out without an account.
 *
 * <h2>Sign in is offered first, and honestly</h2>
 *
 * A returning customer checking out as a guest loses their saved addresses and their order
 * history, and then wonders where their orders went. So signing in is the first option and the
 * guest form is below it — not because guest checkout is second class, but because for somebody
 * who already has an account it genuinely is the worse choice.
 *
 * <h2>What the customer is told</h2>
 *
 * That an account is created and that they can claim it later. Creating one silently and calling
 * it "guest checkout" would be true of the implementation and misleading about what happened —
 * and the customer finds out anyway, the first time they try to reset a password on that address.
 */
export function GuestCheckoutGate() {
  const startGuest = useStartGuestSession();

  const [email, setEmail] = useState('');
  const [fullName, setFullName] = useState('');

  const emailLooksValid = /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim());

  return (
    <div className="mx-auto max-w-lg space-y-6">
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Already have an account?</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <p className="text-sm text-muted-foreground">
            Signing in brings your saved addresses and keeps this order in your history.
          </p>
          <Button asChild className="w-full">
            <Link to={`${paths.login}?redirect=${encodeURIComponent(paths.checkout)}`}>
              Sign in
            </Link>
          </Button>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <UserPlus className="h-4 w-4 text-muted-foreground" aria-hidden />
            Check out as a guest
          </CardTitle>
        </CardHeader>

        <CardContent className="space-y-4">
          <div>
            <Label htmlFor="guest-email">Email address</Label>
            <Input
              id="guest-email"
              type="email"
              autoComplete="email"
              className="mt-2"
              placeholder="ada@example.com"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
            <p className="mt-1 text-xs text-muted-foreground">
              Where your order confirmation goes.
            </p>
          </div>

          <div>
            <Label htmlFor="guest-name">Your name (optional)</Label>
            <Input
              id="guest-name"
              autoComplete="name"
              className="mt-2"
              value={fullName}
              onChange={(event) => setFullName(event.target.value)}
            />
          </div>

          <Button
            className="w-full"
            loading={startGuest.isPending}
            disabled={!emailLooksValid || startGuest.isPending}
            onClick={() =>
              startGuest.mutate({
                email: email.trim(),
                fullName: fullName.trim() || undefined,
              })
            }
          >
            Continue to checkout
          </Button>

          {/* Said plainly. An account is created either way; hiding that would be true of the
              implementation and misleading about what happened, and they find out anyway the
              first time they try to reset a password on this address. */}
          <p className="text-xs text-muted-foreground">
            We create an account for this address so you can follow your order. You can set a
            password whenever you like — use “forgot password” and your orders will already be
            there.
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
