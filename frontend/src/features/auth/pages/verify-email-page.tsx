import { CheckCircle2, LinkIcon, Loader2 } from 'lucide-react';
import { useEffect, useRef } from 'react';
import { Link } from 'react-router-dom';
import { useSearchParams } from 'react-router-dom';

import { Button } from '@/components/ui/button';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';

import { useResendVerification, useVerifyEmail } from '../hooks';

/**
 * The page the confirmation link opens.
 *
 * It verifies on arrival rather than behind a button: the customer already expressed intent by
 * clicking the link in their email, and asking them to confirm the confirmation is a step that
 * exists only to make the page feel busy.
 *
 * The guard against running twice matters more than it looks. Tokens are single use, so a second
 * call would fail — and in development, where effects run twice on mount, the failure path would
 * be the only one anyone ever saw.
 */
export default function VerifyEmailPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');

  const verifyEmail = useVerifyEmail();
  const resend = useResendVerification();
  const attempted = useRef(false);

  const { mutate } = verifyEmail;

  useEffect(() => {
    if (!token || attempted.current) {
      return;
    }
    attempted.current = true;
    mutate(token);
  }, [token, mutate]);

  if (!token) {
    return (
      <Outcome
        tone="warning"
        icon={<LinkIcon className="h-7 w-7" />}
        title="This link is incomplete"
        body="Confirmation links carry a one-time token. Open the link from your email directly — some mail clients trim long URLs."
      >
        <Button asChild>
          <Link to={paths.login}>Go to sign in</Link>
        </Button>
      </Outcome>
    );
  }

  if (verifyEmail.isPending) {
    return (
      <Outcome
        tone="muted"
        icon={<Loader2 className="h-7 w-7 animate-spin" />}
        title="Confirming your address"
        body="One moment."
      />
    );
  }

  if (verifyEmail.isSuccess) {
    return (
      <Outcome
        tone="success"
        icon={<CheckCircle2 className="h-7 w-7" />}
        title="Your email is confirmed"
        body="That is everything. You can sign in and start ordering."
      >
        <Button asChild>
          <Link to={paths.login}>Sign in</Link>
        </Button>
      </Outcome>
    );
  }

  // Almost always an expired or already-used link, and the way out of both is a new one.
  return (
    <Outcome
      tone="warning"
      icon={<LinkIcon className="h-7 w-7" />}
      title="This link no longer works"
      body={
        verifyEmail.isError
          ? normalizeError(verifyEmail.error).message
          : 'It may have expired, or already been used.'
      }
    >
      <ResendForm onResend={(email) => resend.mutate(email)} pending={resend.isPending} />
    </Outcome>
  );
}

/** The shell every state on this page shares, so they cannot drift apart visually. */
function Outcome({
  tone,
  icon,
  title,
  body,
  children,
}: {
  tone: 'success' | 'warning' | 'muted';
  icon: React.ReactNode;
  title: string;
  body: string;
  children?: React.ReactNode;
}) {
  const toneClass =
    tone === 'success'
      ? 'bg-success/10 text-success'
      : tone === 'warning'
        ? 'bg-warning/10 text-warning'
        : 'bg-muted text-muted-foreground';

  return (
    <div className="text-center">
      <span
        className={`mx-auto flex h-14 w-14 items-center justify-center rounded-full ${toneClass}`}
        aria-hidden
      >
        {icon}
      </span>
      <h1 className="mt-4 text-2xl font-semibold tracking-tight">{title}</h1>
      <p className="mt-3 text-sm text-muted-foreground">{body}</p>
      {children ? <div className="mt-8">{children}</div> : null}
    </div>
  );
}

function ResendForm({
  onResend,
  pending,
}: {
  onResend: (email: string) => void;
  pending: boolean;
}) {
  return (
    <form
      className="mx-auto flex max-w-sm flex-col gap-3"
      onSubmit={(event) => {
        event.preventDefault();
        const email = new FormData(event.currentTarget).get('email');
        if (typeof email === 'string' && email.trim()) {
          onResend(email.trim());
        }
      }}
    >
      <label htmlFor="resend-email" className="text-left text-sm font-medium">
        Send a new link
      </label>
      <input
        id="resend-email"
        name="email"
        type="email"
        required
        placeholder="you@example.com"
        className="h-10 w-full rounded-md border bg-background px-3 text-sm outline-none ring-offset-background focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
      />
      <Button type="submit" loading={pending}>
        Send it
      </Button>
      <Link
        to={paths.login}
        className="text-sm text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
      >
        Back to sign in
      </Link>
    </form>
  );
}
