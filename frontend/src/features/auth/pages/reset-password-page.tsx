import { zodResolver } from '@hookform/resolvers/zod';
import { LinkIcon } from 'lucide-react';
import { useForm } from 'react-hook-form';
import { Link, useSearchParams } from 'react-router-dom';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';

import { useResetPassword } from '../hooks';
import { resetPasswordSchema, type ResetPasswordValues } from '../schemas';

/**
 * Step two of a password reset: the page the emailed link opens.
 *
 * The token comes from the query string and is never shown or made editable — it is a credential,
 * and a form field holding one invites it into screenshots and shoulder-surfing. A visitor who
 * arrives without one gets told what happened rather than an input they cannot fill in.
 *
 * On success the customer is sent back to sign in, because they have to: completing a reset
 * revokes every session the account had, including any this browser was holding.
 */
export default function ResetPasswordPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');
  const resetPassword = useResetPassword();

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<ResetPasswordValues>({
    resolver: zodResolver(resetPasswordSchema),
    defaultValues: { token: token ?? '', password: '', confirmPassword: '' },
  });

  if (!token) {
    return (
      <div className="text-center">
        <span
          className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-warning/10 text-warning"
          aria-hidden
        >
          <LinkIcon className="h-7 w-7" />
        </span>

        <h1 className="mt-4 text-2xl font-semibold tracking-tight">This link is incomplete</h1>

        <p className="mt-3 text-sm text-muted-foreground">
          Reset links carry a one-time token. Open the link from your email directly, or ask for a
          new one — some mail clients trim long URLs.
        </p>

        <div className="mt-8 flex flex-wrap justify-center gap-3">
          <Button asChild>
            <Link to={paths.forgotPassword}>Request a new link</Link>
          </Button>
          <Button variant="ghost" asChild>
            <Link to={paths.login}>Back to sign in</Link>
          </Button>
        </div>
      </div>
    );
  }

  const onSubmit = handleSubmit(async (values) => {
    await resetPassword.mutateAsync({ token: values.token, password: values.password });
  });

  const failure = resetPassword.isError ? normalizeError(resetPassword.error) : null;

  return (
    <div>
      <div className="mb-8">
        <h1 className="text-2xl font-semibold tracking-tight">Set a new password</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          Choose something you have not used here before. Everything currently signed in will be
          signed out.
        </p>
      </div>

      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        {/* The token rides along in the form but is never rendered: it is a credential, and an
            editable field is the wrong shape for one. */}
        <input type="hidden" {...register('token')} />

        <div className="space-y-2">
          <Label htmlFor="password">New password</Label>
          <Input
            id="password"
            type="password"
            autoComplete="new-password"
            autoFocus
            aria-invalid={Boolean(errors.password)}
            aria-describedby={errors.password ? 'password-error' : 'password-hint'}
            {...register('password')}
          />
          {errors.password ? (
            <p id="password-error" className="text-sm text-destructive">
              {errors.password.message}
            </p>
          ) : (
            <p id="password-hint" className="text-xs text-muted-foreground">
              At least 8 characters, with a letter and a digit.
            </p>
          )}
        </div>

        <div className="space-y-2">
          <Label htmlFor="confirmPassword">Confirm new password</Label>
          <Input
            id="confirmPassword"
            type="password"
            autoComplete="new-password"
            aria-invalid={Boolean(errors.confirmPassword)}
            aria-describedby={errors.confirmPassword ? 'confirm-error' : undefined}
            {...register('confirmPassword')}
          />
          {errors.confirmPassword ? (
            <p id="confirm-error" className="text-sm text-destructive">
              {errors.confirmPassword.message}
            </p>
          ) : null}
        </div>

        {/* An expired or already-used link is the common failure here, and the only useful next
            step is a new one — so the message carries the way out with it. */}
        {failure ? (
          <div className="rounded-md border border-destructive/40 bg-destructive/5 p-3">
            <p className="text-sm">{failure.message}</p>
            <Link
              to={paths.forgotPassword}
              className="mt-2 inline-block text-sm font-medium underline-offset-4 hover:underline"
            >
              Request a new link
            </Link>
          </div>
        ) : null}

        <Button type="submit" className="w-full" loading={isSubmitting || resetPassword.isPending}>
          Change password
        </Button>
      </form>

      <p className="mt-6 text-center text-sm text-muted-foreground">
        <Link
          to={paths.login}
          className="font-medium text-foreground underline-offset-4 hover:underline"
        >
          Back to sign in
        </Link>
      </p>
    </div>
  );
}
