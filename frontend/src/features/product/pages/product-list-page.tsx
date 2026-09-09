import { useCallback, useMemo } from 'react';

import { PageHeader } from '@/components/common/page-header';
import { PagePagination } from '@/components/common/pagination';
import { useQueryParams } from '@/hooks/use-query-params';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { DEFAULT_PAGE_SIZE } from '@/shared/config';

import { ProductFilters } from '../components/product-filters';
import { ProductGrid } from '../components/product-grid';
import { useProducts } from '../hooks';

/**
 * Filter defaults.
 *
 * Frozen at module scope so its identity is stable — `useQueryParams` memoises against it, and a
 * fresh object literal every render would defeat that and re-derive on every keystroke.
 */
const DEFAULTS = {
  page: 0,
  size: DEFAULT_PAGE_SIZE,
  search: '',
  category: '',
  sortBy: 'name',
  direction: 'asc' as 'asc' | 'desc',
};

/**
 * The catalogue.
 *
 * All list state lives in the URL, which is what makes a filtered view shareable and the back
 * button behave. It also means the TanStack Query key derives from the URL, so navigating back
 * hits a warm cache instead of refetching.
 */
export default function ProductListPage() {
  const { params, setParams, resetParams } = useQueryParams(DEFAULTS);

  const query = useProducts({
    page: params.page,
    size: params.size,
    search: params.search || undefined,
    category: params.category || undefined,
    activeOnly: true,
    sortBy: params.sortBy,
    direction: params.direction,
  });

  /** Any filter change resets to page 0 — page 7 of the old result set is meaningless. */
  const handleFilterChange = useCallback(
    (patch: Partial<typeof DEFAULTS>) => setParams({ ...patch, page: 0 }),
    [setParams],
  );

  const filterValues = useMemo(
    () => ({
      search: params.search,
      category: params.category,
      sortBy: params.sortBy,
      direction: params.direction,
    }),
    [params.search, params.category, params.sortBy, params.direction],
  );

  return (
    <div>
      <PageHeader
        title="Shop"
        description="Everything in the catalogue."
        breadcrumbs={[{ label: 'Home', to: paths.home }, { label: 'Shop' }]}
      />

      <div className="space-y-6">
        <ProductFilters
          values={filterValues}
          onChange={handleFilterChange}
          onReset={resetParams}
          totalElements={query.data?.totalElements}
        />

        <ProductGrid
          products={query.data?.content}
          isLoading={query.isLoading}
          error={query.error ? normalizeError(query.error) : null}
          onRetry={() => void query.refetch()}
          skeletonCount={params.size}
        />

        <PagePagination data={query.data} onPageChange={(page) => setParams({ page })} />
      </div>
    </div>
  );
}
