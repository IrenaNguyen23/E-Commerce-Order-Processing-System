import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';

/**
 * Keeps list state — page, filters, sort, search — in the URL rather than in component state.
 *
 * This is a deliberate choice with real consequences: a filtered product list becomes shareable,
 * the back button works the way a user expects, and a refresh does not silently reset the view.
 * It also means TanStack Query keys derive from the URL, so navigation history and cache entries
 * line up naturally.
 */
export function useQueryParams<T extends Record<string, string | number | boolean | undefined>>(
  defaults: T,
) {
  const [searchParams, setSearchParams] = useSearchParams();

  const params = useMemo(() => {
    const result = { ...defaults };

    (Object.keys(defaults) as Array<keyof T>).forEach((key) => {
      const raw = searchParams.get(String(key));
      if (raw === null) return;

      const fallback = defaults[key];
      if (typeof fallback === 'number') {
        const parsed = Number(raw);
        if (Number.isFinite(parsed)) {
          result[key] = parsed as T[keyof T];
        }
      } else if (typeof fallback === 'boolean') {
        result[key] = (raw === 'true') as T[keyof T];
      } else {
        result[key] = raw as T[keyof T];
      }
    });

    return result;
  }, [searchParams, defaults]);

  /**
   * Merges a partial update into the URL.
   *
   * A value equal to its default is removed rather than written, which keeps the URL short and
   * makes "no filters" a clean `/products` instead of `/products?page=0&size=12&activeOnly=true`.
   */
  const setParams = useCallback(
    (updates: Partial<T>, options?: { replace?: boolean }) => {
      setSearchParams(
        (current) => {
          const next = new URLSearchParams(current);

          Object.entries(updates).forEach(([key, value]) => {
            const isDefault = value === defaults[key as keyof T];
            if (value === undefined || value === null || value === '' || isDefault) {
              next.delete(key);
            } else {
              next.set(key, String(value));
            }
          });

          return next;
        },
        { replace: options?.replace ?? false },
      );
    },
    [setSearchParams, defaults],
  );

  const resetParams = useCallback(() => {
    setSearchParams(new URLSearchParams(), { replace: false });
  }, [setSearchParams]);

  return { params, setParams, resetParams };
}
