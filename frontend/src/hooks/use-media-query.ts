import { useEffect, useState } from 'react';

/**
 * Subscribes to a CSS media query.
 *
 * Only for cases where the difference is structural — rendering a drawer instead of a sidebar,
 * a card list instead of a table. Purely visual differences belong in Tailwind classes, which
 * cost nothing at runtime.
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() =>
    typeof window !== 'undefined' ? window.matchMedia(query).matches : false,
  );

  useEffect(() => {
    const mediaQuery = window.matchMedia(query);
    const listener = (event: MediaQueryListEvent) => setMatches(event.matches);

    setMatches(mediaQuery.matches);
    mediaQuery.addEventListener('change', listener);
    return () => mediaQuery.removeEventListener('change', listener);
  }, [query]);

  return matches;
}

/** Tailwind's `md` breakpoint. */
export const useIsMobile = () => !useMediaQuery('(min-width: 768px)');
