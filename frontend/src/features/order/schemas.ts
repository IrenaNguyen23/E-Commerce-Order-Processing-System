import { z } from 'zod';

/**
 * Checkout validation.
 *
 * The shape here mirrors the API's `PostalAddress` exactly, rather than being a client-side
 * convenience that gets composed into a string on submit. That mattered enough to change: the
 * country code decides the tax rate and the delivery charge, so it has to survive the trip as a
 * code rather than as whatever the customer typed into a "Country" box.
 *
 * Only recipient, street, city and country are required. Postcodes and regions are optional
 * because much of the world does not have them, and a form that insists produces "N/A" typed into
 * a field a courier will later try to read.
 */

const trimmed = (max: number, tooLong: string) =>
  z.string().max(max, tooLong).transform((value) => value.trim());

const optionalText = (max: number, tooLong: string) =>
  z.string().max(max, tooLong).optional().or(z.literal(''));

export const addressSchema = z.object({
  label: optionalText(50, 'Label is too long'),

  recipientName: trimmed(150, 'Name is too long').pipe(
    z.string().min(1, 'Recipient name is required'),
  ),

  line1: trimmed(200, 'Address is too long').pipe(
    z.string().min(1, 'Street address is required'),
  ),

  line2: optionalText(200, 'Address is too long'),

  city: trimmed(100, 'City is too long').pipe(z.string().min(1, 'City is required')),

  region: optionalText(100, 'Region is too long'),

  postalCode: optionalText(20, 'Postal code is too long'),

  /**
   * Two letters, and upper-cased before it leaves the form.
   *
   * A lower-case code matches no tax rate and no shipping zone on the server. The failure is not
   * an error — it is a total that is quietly wrong — so it is worth catching in the form.
   */
  countryCode: z
    .string()
    .min(1, 'Country is required')
    .regex(/^[A-Za-z]{2}$/, 'Use a two-letter country code, e.g. NL')
    .transform((value) => value.trim().toUpperCase()),

  phone: optionalText(32, 'Phone number is too long'),
});

export const checkoutSchema = z.object({
  address: addressSchema,
  shippingMethod: z.enum(['STANDARD', 'EXPRESS', 'PICKUP']).default('STANDARD'),
  saveAddress: z.boolean().default(true),
});

export type AddressValues = z.infer<typeof addressSchema>;
export type CheckoutValues = z.infer<typeof checkoutSchema>;
