import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { Link, useSearchParams } from 'react-router-dom';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { paths, safeRedirect } from '@/routes/paths';

import { applyServerErrors, useLogin } from '../hooks';
import { loginSchema, type LoginValues } from '../schemas';

export default function LoginPage() {
  const [searchParams] = useSearchParams();
  // Set by the auth guard, so an expired session on /checkout returns to /checkout.
  // Validated, never trusted: the parameter is attacker-controlled and an unchecked value here
  // is an open redirect straight out of our own login page.
  const redirectTo = safeRedirect(searchParams.get('redirect'));

  const login = useLogin(redirectTo);

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: '', password: '' },
  });

  const onSubmit = handleSubmit(async (values) => {
    try {
      await login.mutateAsync(values);
    } catch (error) {
      applyServerErrors(error, setError as never, ['email', 'password']);
    }
  });

  return (
    <div>
      <div className="mb-8">
        <h1 className="text-2xl font-semibold tracking-tight">Sign in</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          Welcome back. Enter your details to continue.
        </p>
      </div>

      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        <div className="space-y-2">
          <Label htmlFor="email">Email</Label>
          <Input
            id="email"
            type="email"
            autoComplete="email"
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

        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <Label htmlFor="password">Password</Label>
            <Link
              to={paths.forgotPassword}
              className="text-sm text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
            >
              Forgot password?
            </Link>
          </div>
          <Input
            id="password"
            type="password"
            autoComplete="current-password"
            aria-invalid={Boolean(errors.password)}
            aria-describedby={errors.password ? 'password-error' : undefined}
            {...register('password')}
          />
          {errors.password ? (
            <p id="password-error" className="text-sm text-destructive">
              {errors.password.message}
            </p>
          ) : null}
        </div>

        <Button type="submit" className="w-full" loading={isSubmitting || login.isPending}>
          Sign in
        </Button>
      </form>

      <p className="mt-6 text-center text-sm text-muted-foreground">
        No account yet?{' '}
        <Link to={paths.register} className="font-medium text-foreground underline-offset-4 hover:underline">
          Create one
        </Link>
      </p>

      {/* The compose stack seeds this account, so the demo is usable without registering. */}
      <div className="mt-8 rounded-lg border bg-muted/40 p-4">
        <p className="text-xs font-medium">Demo administrator</p>
        <p className="mt-1 font-mono text-xs text-muted-foreground">
          admin@commerceflow.io / ChangeMe-Admin-2026
        </p>
      </div>
    </div>
  );
}
