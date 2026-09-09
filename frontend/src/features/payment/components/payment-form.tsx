import { Elements, PaymentElement, useElements, useStripe } from '@stripe/react-stripe-js';
import { loadStripe, type Stripe } from '@stripe/stripe-js';
import { CreditCard } from 'lucide-react';
import { useState } from 'react';

import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { config } from '@/shared/config';
import { formatMoney } from '@/utils/format';

/**
 * Where the customer actually pays.
 *
 * The order already exists and its stock is already held by the time this appears — that ordering
 * is what makes the whole saga safe, and it is why payment happens here rather than before the
 * order was placed. The cost is this screen: the customer has to finish something they started.
 *
 * Nothing here handles success. Confirming tells Stripe, Stripe tells the backend over a webhook,
 * and the webhook advances the saga; the page around this is already polling the order and will
 * notice on its own. Marking the order paid from the browser would mean trusting the browser
 * about whether money moved.
 */

/**
 * Loaded once for the lifetime of the tab.
 *
 * `loadStripe` injects a script tag, so calling it per render would add one on every keystroke.
 */
let stripePromise: Promise<Stripe | null> | null = null;

function stripe(): Promise<Stripe | null> {
  stripePromise ??= loadStripe(config.stripePublishableKey);
  return stripePromise;
}

interface PaymentFormProps {
  clientSecret: string;
  amount: number;
  currency: string;
}

export function PaymentForm({ clientSecret, amount, currency }: PaymentFormProps) {
  // Without a publishable key there is nothing to mount. That is the normal state on the
  // simulated acquirer, not an error, so it renders nothing rather than an apology.
  if (!config.stripePublishableKey) {
    return null;
  }

  return (
    <Card className="mb-6 border-primary/40">
      <CardContent className="p-6">
        <div className="mb-4 flex items-center gap-2">
          <CreditCard className="h-5 w-5 text-primary" aria-hidden />
          <h2 className="font-semibold">Complete your payment</h2>
        </div>

        <p className="mb-5 text-sm text-muted-foreground">
          Your items are reserved. Pay {formatMoney(amount, currency)} to confirm the order — we
          will hold them while you do.
        </p>

        <Elements
          stripe={stripe()}
          options={{ clientSecret, appearance: { theme: 'stripe' } }}
        >
          <ConfirmPayment />
        </Elements>
      </CardContent>
    </Card>
  );
}

function ConfirmPayment() {
  const stripeInstance = useStripe();
  const elements = useElements();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const ready = Boolean(stripeInstance && elements);

  const onSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!stripeInstance || !elements) {
      return;
    }

    setSubmitting(true);
    setError(null);

    // `redirect: 'if_required'` keeps the customer on this page for cards, which is most of
    // them, while still allowing methods that genuinely have to leave — iDEAL, a bank app — to
    // do so. Forcing a redirect for everything would throw away the live order timeline.
    const result = await stripeInstance.confirmPayment({
      elements,
      redirect: 'if_required',
      confirmParams: { return_url: window.location.href },
    });

    setSubmitting(false);

    if (result.error) {
      // Stripe's own message is better than anything generic: it distinguishes an expired card
      // from insufficient funds from a typo, and the customer can act on each differently.
      setError(result.error.message ?? 'That payment could not be completed.');
      return;
    }

    // Deliberately no success state. The webhook is what makes the order paid, and the timeline
    // above this form updates when it lands. Claiming success here would be the browser telling
    // the customer something only the backend can know.
  };

  return (
    <form onSubmit={onSubmit} className="space-y-4">
      <PaymentElement />

      {error ? (
        <div
          role="alert"
          className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm"
        >
          {error}
        </div>
      ) : null}

      <Button type="submit" className="w-full" disabled={!ready} loading={submitting}>
        Pay now
      </Button>

      <p className="text-center text-xs text-muted-foreground">
        Card details go straight to Stripe. They never reach CommerceFlow&rsquo;s servers.
      </p>
    </form>
  );
}
