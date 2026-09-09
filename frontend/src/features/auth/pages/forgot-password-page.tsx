import { zodResolver } from '@hookform/resolvers/zod';
import { MailCheck } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { paths } from '@/routes/paths';

import { useForgotPassword } from '../hooks';
import { forgotPasswordSchema, type ForgotPasswordValues } from '../schemas';

/**
 * Step one of a password reset.
 *
 * The confirmation screen says *if an account exists* rather than *we have emailed you*, and that
 * wording is load-bearing rather than lawyerly. The backend answers identically whether or not
 * the address is registered, so that nobody can use this form to find out who has an account —
 * and a screen that claimed an email was sent would give the game away as surely as an error
 * message would.
 *
 * It costs a worse experience for someone who mistypes their address: they are told to check
 * their inbox and nothing arrives. That is the trade, made deliberately.
 */
export default function ForgotPasswordPage() {
  const [submittedTo, setSubmittedTo] = useState<string | null>(null);
  const forgotPassword = useForgotPassword();

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<ForgotPasswordValues>({
    resolver: zodResolver(forgotPasswordSchema),
    defaultValues: { email: '' },
  });

  const onSubmit = handleSubmit(async (values) => {
    await forgotPassword.mutateAsync(values.email);
    setSubmittedTo(values.email);
  });

  if (submittedTo) {
    return (
      <div>
        <span
          className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-primary/10 text-primary"
          aria-hidden
        >
          <MailCheck className="h-7 w-7" />
        </span>

        <h1 className="mt-4 text-center text-2xl font-semibold tracking-tight">Check your inbox</h1>

        <p className="mt-3 text-center text-sm text-muted-foreground">
          If an account exists for <span className="font-medium text-foreground">{submittedTo}</span>,
          a reset link is on its way. It works once and expires in 30 minutes.
        </p>

        <p className="mt-6 text-center text-sm text-muted-foreground">
          Nothing arrived? Check your spam folder, or{' '}
          <button
            type="button"
            onClick={() => setSubmittedTo(null)}
            className="font-medium text-foreground underline-offset-4 hover:underline"
          >
            try another address
          </button>
          .
        </p>

        <div className="mt-8 text-center">
          <Button variant="outline" asChild>
            <Link to={paths.login}>Back to sign in</Link>
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div>
      <div className="mb-8">
        <h1 className="text-2xl font-semibold tracking-tight">Forgot your password?</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          Enter the address you signed up with and we will send you a link to set a new one.
        </p>
      </div>

      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        <div className="space-y-2">
          <Label htmlFor="email">Email</Label>
          <Input
            id="email"
            type="email"
            autoComplete="email"
            autoFocus
            placeholder="you@example.com"
            aria-invalid={Boolean(errors.email)}
            aria-describedby={errors.email ? 'email-error' : undefined}
            {...register('email')}
          />
          {errors.email ? (
            <p id="email-error" className="text-sm text-destructive">
              {errors.email.message}
            </p>
          ) : null}
        </div>

        <Button
          type="submit"
          className="w-full"
          loading={isSubmitting || forgotPassword.isPending}
        >
          Send reset link
        </Button>
      </form>

      <p className="mt-6 text-center text-sm text-muted-foreground">
        Remembered it?{' '}
        <Link
          to={paths.login}
          className="font-medium text-foreground underline-offset-4 hover:underline"
        >
          Sign in
        </Link>
      </p>
    </div>
  );
}
