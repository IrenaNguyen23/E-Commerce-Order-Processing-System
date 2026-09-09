import { useEffect, useState } from 'react';

/**
 * Returns `value` only after it has stopped changing for `delay` ms.
 *
 * Used for search: the debounced value becomes the TanStack Query key, so an intermediate
 * keystroke never produces a request that is immediately discarded.
 */
export function useDebounce<T>(value: T, delay = 350): T {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);

  return debounced;
}
