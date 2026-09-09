import { type ClassValue, clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

/**
 * Merges class names, with later Tailwind utilities winning over earlier ones.
 *
 * `clsx` handles the conditionals; `twMerge` resolves conflicts (`px-2 px-4` -> `px-4`), which is
 * what makes a `className` prop on a styled component actually able to override its defaults.
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
