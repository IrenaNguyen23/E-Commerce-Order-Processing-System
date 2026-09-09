import { Search, X } from 'lucide-react';
import { useEffect, useState } from 'react';

import { Input } from '@/components/ui/input';
import { useDebounce } from '@/hooks/use-debounce';
import { cn } from '@/utils/cn';

interface SearchInputProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  /** Milliseconds to wait after the last keystroke. 350 ms is short enough to feel live. */
  delay?: number;
  className?: string;
  autoFocus?: boolean;
}

/**
 * Debounced search box.
 *
 * The input stays fully responsive (local state) while the debounced value is what propagates
 * upward — so it becomes the query key, and no request is made for an intermediate keystroke.
 */
export function SearchInput({
  value,
  onChange,
  placeholder = 'Search…',
  delay = 350,
  className,
  autoFocus,
}: SearchInputProps) {
  const [draft, setDraft] = useState(value);
  const debounced = useDebounce(draft, delay);

  // Push the settled value up. Guarded so re-renders from the parent do not loop back in.
  useEffect(() => {
    if (debounced !== value) {
      onChange(debounced);
    }
    // `value` and `onChange` are deliberately omitted: this effect fires on a settled keystroke,
    // not when the parent happens to re-render with the same value.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debounced]);

  // Keep in sync when the parent resets the term (clearing filters, navigating).
  useEffect(() => {
    setDraft(value);
  }, [value]);

  return (
    <div className={cn('relative', className)}>
      <Search
        className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground"
        aria-hidden
      />
      <Input
        type="search"
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        placeholder={placeholder}
        className="pl-9 pr-9"
        autoFocus={autoFocus}
        aria-label={placeholder}
      />
      {draft ? (
        <button
          type="button"
          onClick={() => setDraft('')}
          className="absolute right-2 top-1/2 -translate-y-1/2 rounded-sm p-1 text-muted-foreground transition-colors hover:text-foreground"
          aria-label="Clear search"
        >
          <X className="h-4 w-4" />
        </button>
      ) : null}
    </div>
  );
}
