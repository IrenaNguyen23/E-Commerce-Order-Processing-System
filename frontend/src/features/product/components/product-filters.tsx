import { SlidersHorizontal, X } from 'lucide-react';

import { SearchInput } from '@/components/common/search-input';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { useCategories } from '../hooks';

export interface ProductFilterValues {
  search: string;
  category: string;
  sortBy: string;
  direction: 'asc' | 'desc';
}

interface ProductFiltersProps {
  values: ProductFilterValues;
  onChange: (patch: Partial<ProductFilterValues>) => void;
  onReset: () => void;
  /** Hide the category picker on a page that is already scoped to one. */
  showCategory?: boolean;
  totalElements?: number;
}

/**
 * Sort options.
 *
 * Deliberately limited to the fields Inventory Service actually sorts by — anything else is
 * silently replaced server-side, which would show the user a sort that does nothing.
 */
const SORT_OPTIONS = [
  { value: 'name:asc', label: 'Name (A–Z)' },
  { value: 'name:desc', label: 'Name (Z–A)' },
  { value: 'price:asc', label: 'Price (low to high)' },
  { value: 'price:desc', label: 'Price (high to low)' },
  { value: 'createdAt:desc', label: 'Newest first' },
] as const;

const ALL_CATEGORIES = '__all__';

export function ProductFilters({
  values,
  onChange,
  onReset,
  showCategory = true,
  totalElements,
}: ProductFiltersProps) {
  const categories = useCategories();

  const sortValue = `${values.sortBy}:${values.direction}`;
  const hasActiveFilters = Boolean(values.search || values.category);

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <SearchInput
          value={values.search}
          onChange={(search) => onChange({ search })}
          placeholder="Search products…"
          className="sm:max-w-xs"
        />

        {showCategory ? (
          <Select
            value={values.category || ALL_CATEGORIES}
            onValueChange={(category) =>
              onChange({ category: category === ALL_CATEGORIES ? '' : category })
            }
          >
            <SelectTrigger className="sm:w-48" aria-label="Filter by category">
              <SelectValue placeholder="All categories" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL_CATEGORIES}>All categories</SelectItem>
              {/* Valued by slug, labelled by name. A bookmarked filter then survives a
                  merchandiser renaming the section. */}
              {categories.data?.map((category) => (
                <SelectItem key={category.slug} value={category.slug}>
                  {category.name} ({category.productCount})
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        ) : null}

        <Select
          value={sortValue}
          onValueChange={(value) => {
            const [sortBy, direction] = value.split(':');
            onChange({ sortBy, direction: direction as 'asc' | 'desc' });
          }}
        >
          <SelectTrigger className="sm:w-52" aria-label="Sort products">
            <span className="flex items-center gap-2">
              <SlidersHorizontal className="h-4 w-4 text-muted-foreground" aria-hidden />
              <SelectValue />
            </span>
          </SelectTrigger>
          <SelectContent>
            {SORT_OPTIONS.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {(hasActiveFilters || totalElements !== undefined) && (
        <div className="flex flex-wrap items-center gap-2">
          {totalElements !== undefined ? (
            <p className="text-sm text-muted-foreground tabular">
              {totalElements} {totalElements === 1 ? 'product' : 'products'}
            </p>
          ) : null}

          {values.search ? (
            <FilterChip label={`“${values.search}”`} onClear={() => onChange({ search: '' })} />
          ) : null}

          {values.category ? (
            <FilterChip
              // The chip shows the name, not the slug the filter is keyed on. "computers" in a
              // chip reads as a bug to somebody who selected "Computers".
              label={
                categories.data?.find((category) => category.slug === values.category)?.name ??
                values.category
              }
              onClear={() => onChange({ category: '' })}
            />
          ) : null}

          {hasActiveFilters ? (
            <Button variant="ghost" size="sm" onClick={onReset}>
              Clear all
            </Button>
          ) : null}
        </div>
      )}
    </div>
  );
}

function FilterChip({ label, onClear }: { label: string; onClear: () => void }) {
  return (
    <Badge variant="secondary" className="gap-1 pr-1">
      {label}
      <button
        type="button"
        onClick={onClear}
        className="rounded-full p-0.5 transition-colors hover:bg-background"
        aria-label={`Remove filter ${label}`}
      >
        <X className="h-3 w-3" />
      </button>
    </Badge>
  );
}
