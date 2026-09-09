/**
 * `localStorage` that cannot throw.
 *
 * Private browsing, disabled storage and quota errors all raise from the native API. A cart or a
 * wishlist failing to persist is a degraded experience; an unhandled exception on read is a white
 * screen. This trades the former for never the latter.
 */
export const safeStorage = {
  read<T>(key: string, fallback: T): T {
    try {
      const raw = localStorage.getItem(key);
      return raw ? (JSON.parse(raw) as T) : fallback;
    } catch {
      return fallback;
    }
  },

  write(key: string, value: unknown): boolean {
    try {
      localStorage.setItem(key, JSON.stringify(value));
      return true;
    } catch {
      return false;
    }
  },

  remove(key: string): void {
    try {
      localStorage.removeItem(key);
    } catch {
      /* nothing meaningful to do */
    }
  },
};
