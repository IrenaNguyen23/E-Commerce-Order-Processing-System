import { z } from 'zod';

/**
 * Validation, mirrored from the backend's bean-validation constraints.
 *
 * Client-side rules exist to give instant feedback, not to be the security boundary — the server
 * re-validates everything. Where they differ from the backend the backend wins, and its
 * `fieldErrors` are mapped straight onto these same field names.
 */

const email = z
  .string()
  .min(1, 'Email is required')
  .email('Enter a valid email address')
  .max(255, 'Email is too long');

/** Backend: 8–72 chars, at least one letter and one digit. */
const password = z
  .string()
  .min(8, 'Password must be at least 8 characters')
  .max(72, 'Password must be 72 characters or fewer')
  .refine((value) => /[A-Za-z]/.test(value), 'Password must contain a letter')
  .refine((value) => /[0-9]/.test(value), 'Password must contain a digit');

export const loginSchema = z.object({
  email,
  // Login only checks presence: an existing account may predate any rule change, and telling a
  // user their stored password is "invalid" before even trying it is nonsense.
  password: z.string().min(1, 'Password is required'),
});

export const registerSchema = z
  .object({
    email,
    password,
    confirmPassword: z.string().min(1, 'Please confirm your password'),
    fullName: z
      .string()
      .min(1, 'Full name is required')
      .max(150, 'Full name is too long')
      .transform((value) => value.trim()),
    phone: z
      .string()
      .max(32, 'Phone number is too long')
      .optional()
      .or(z.literal('')),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  });

export const forgotPasswordSchema = z.object({ email });

export const resetPasswordSchema = z
  .object({
    token: z.string().min(1, 'Reset token is required'),
    password,
    confirmPassword: z.string().min(1, 'Please confirm your password'),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  });

export type LoginValues = z.infer<typeof loginSchema>;
export type RegisterValues = z.infer<typeof registerSchema>;
export type ForgotPasswordValues = z.infer<typeof forgotPasswordSchema>;
export type ResetPasswordValues = z.infer<typeof resetPasswordSchema>;
