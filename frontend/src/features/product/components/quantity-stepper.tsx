import { Minus, Plus } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/utils/cn';

interface QuantityStepperProps {
  value: number;
  onChange: (value: number) => void;
  min?: number;
  /** Usually the live `availableQuantity`, so the control cannot exceed real stock. */
  max?: number;
  disabled?: boolean;
  size?: 'sm' | 'default';
  className?: string;
  label?: string;
}

/**
 * Quantity control.
 *
 * Clamping happens here rather than at the call site, so no screen can push a quantity past
 * available stock or below one — the cart, the product page and the checkout all get the same
 * guarantee from the same component.
 */
export function QuantityStepper({
  value,
  onChange,
  min = 1,
  max = 999,
  disabled = false,
  size = 'default',
  className,
  label = 'Quantity',
}: QuantityStepperProps) {
  const clamp = (next: number) => Math.max(min, Math.min(max, next));

  const buttonSize = size === 'sm' ? 'h-8 w-8' : 'h-10 w-10';
  const inputSize = size === 'sm' ? 'h-8 w-12' : 'h-10 w-16';

  return (
    <div className={cn('inline-flex items-center rounded-md border', className)} role="group" aria-label={label}>
      <Button
        type="button"
        variant="ghost"
        className={cn(buttonSize, 'rounded-r-none')}
        onClick={() => onChange(clamp(value - 1))}
        disabled={disabled || value <= min}
        aria-label="Decrease quantity"
      >
        <Minus aria-hidden />
      </Button>

      <Input
        type="number"
        inputMode="numeric"
        value={value}
        min={min}
        max={max}
        disabled={disabled}
        onChange={(event) => {
          const parsed = Number(event.target.value);
          // An empty or non-numeric field must not wipe the quantity — hold the current value
          // until the user types something usable.
          if (Number.isFinite(parsed) && parsed > 0) {
            onChange(clamp(parsed));
          }
        }}
        className={cn(
          inputSize,
          'rounded-none border-0 border-x px-0 text-center tabular',
          '[appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none',
        )}
        aria-label={label}
      />

      <Button
        type="button"
        variant="ghost"
        className={cn(buttonSize, 'rounded-l-none')}
        onClick={() => onChange(clamp(value + 1))}
        disabled={disabled || value >= max}
        aria-label="Increase quantity"
      >
        <Plus aria-hidden />
      </Button>
    </div>
  );
}
