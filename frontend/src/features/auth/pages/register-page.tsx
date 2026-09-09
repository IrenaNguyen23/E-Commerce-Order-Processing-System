import { zodResolver } from '@hookform/resolvers/zod';
import { Check } from 'lucide-react';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { paths } from '@/routes/paths';
import { cn } from '@/utils/cn';

import { applyServerErrors, useRegister } from '../hooks';
import { registerSchema, type RegisterValues } from '../schemas';

export default function RegisterPage() {
  const registerMutation = useRegister();

  const {
    register,
    handleSubmit,
    setError,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<RegisterValues>({
    resolver: zodResolver(registerSchema),
    // Validate as the user corrects a field, not on every keystroke from the start — the former
    // is helpful, the latter shouts at someone halfway through typing their email.
    mode: 'onTouched',
    defaultValues: { email: '', password: '', confirmPassword: '', fullName: '', phone: '' },
  });

  const password = watch('password') ?? '';

  const onSubmit = handleSubmit(async (values) => {
    try {
      await registerMutation.mutateAsync({
        email: values.email,
        password: values.password,
        fullName: values.fullName,
        phone: values.phone || undefined,
      });
    } catch (error) {
      applyServerErrors(error, setError as never, ['email', 'password', 'fullName', 'phone']);
    }
  });

  return (
    <div>
      <div className="mb-8">
        <h1 className="text-2xl font-semibold tracking-tight">Create your account</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          It takes a moment. You will be signed in straight away.
        </p>
      </div>

      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        <Field
          id="fullName"
          label="Full name"
          autoComplete="name"
          placeholder="Ada Lovelace"
          error={errors.fullName?.message}
          {...register('fullName')}
        />

        <Field
          id="email"
          label="Email"
          type="email"
          autoComplete="email"
          placeholder="you@example.com"
          error={errors.email?.message}
          {...register('email')}
        />

        <Field
          id="phone"
          label="Phone"
          optional
          type="tel"
          autoComplete="tel"
          placeholder="+31 6 1234 5678"
          error={errors.phone?.message}
          {...register('phone')}
        />

        <div className="space-y-2">
          <Label htmlFor="password">Password</Label>
          <Input
            id="password"
            type="password"
            autoComplete="new-password"
            aria-invalid={Boolean(errors.password)}
            {...register('password')}
          />
          {/* Live requirements beat an error after submit: the rules are visible while typing. */}
          <ul className="space-y-1 pt-1">
            <Requirement met={password.length >= 8}>At least 8 characters</Requirement>
            <Requirement met={/[A-Za-z]/.test(password)}>Contains a letter</Requirement>
            <Requirement met={/[0-9]/.test(password)}>Contains a digit</Requirement>
          </ul>
          {errors.password ? (
            <p className="text-sm text-destructive">{errors.password.message}</p>
          ) : null}
        </div>

        <Field
          id="confirmPassword"
          label="Confirm password"
          type="password"
          autoComplete="new-password"
          error={errors.confirmPassword?.message}
          {...register('confirmPassword')}
        />

        <Button
          type="submit"
          className="w-full"
          loading={isSubmitting || registerMutation.isPending}
        >
          Create account
        </Button>
      </form>

      <p className="mt-6 text-center text-sm text-muted-foreground">
        Already registered?{' '}
        <Link to={paths.login} className="font-medium text-foreground underline-offset-4 hover:underline">
          Sign in
        </Link>
      </p>
    </div>
  );
}

const Field = ({
  id,
  label,
  error,
  optional,
  ...props
}: React.InputHTMLAttributes<HTMLInputElement> & {
  id: string;
  label: string;
  error?: string;
  optional?: boolean;
}) => (
  <div className="space-y-2">
    <Label htmlFor={id}>
      {label}
      {optional ? <span className="ml-1 text-muted-foreground">(optional)</span> : null}
    </Label>
    <Input id={id} aria-invalid={Boolean(error)} {...props} />
    {error ? <p className="text-sm text-destructive">{error}</p> : null}
  </div>
);

function Requirement({ met, children }: { met: boolean; children: React.ReactNode }) {
  return (
    <li
      className={cn(
        'flex items-center gap-2 text-xs',
        met ? 'text-success' : 'text-muted-foreground',
      )}
    >
      <Check className={cn('h-3 w-3', !met && 'opacity-30')} aria-hidden />
      {children}
    </li>
  );
}
