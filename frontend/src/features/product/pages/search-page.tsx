import { Search } from 'lucide-react';
import { useCallback, useMemo } from 'react';
import { Link } from 'react-router-dom';

import { PageHeader } from '@/components/common/page-header';
import { PagePagination } from '@/components/common/pagination';
import { Button } from '@/components/ui/button';
import { useQueryParams } from '@/hooks/use-query-params';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { DEFAULT_PAGE_SIZE } from '@/shared/config';

import { ProductFilters } from '../components/product-filters';
import { ProductGrid } from '../components/product-grid';
import { useProducts } from '../hooks';

/** `q` rather than `search`, because that is what the header links to and what users expect. */
const DEFAULTS = {
  q: '',
  page: 0,
  size: DEFAULT_PAGE_SIZE,
  category: '',
  sortBy: 'name',
  direction: 'asc' as 'asc' | 'desc',
};

export default function SearchPage() {
  const { params, setParams, resetParams } = useQueryParams(DEFAULTS);
  const term = params.q.trim();

  const query = useProducts({
    page: params.page,
    size: params.size,
    search: term || undefined,
    category: params.category || undefined,
    activeOnly: true,
    sortBy: params.sortBy,
    direction: params.direction,
  });

  const handleFilterChange = useCallback(
    (patch: { search?: string; category?: string; sortBy?: string; direction?: 'asc' | 'desc' }) => {
      const { search, ...rest } = patch;
      setParams({ ...rest, ...(search !== undefined ? { q: search } : {}), page: 0 });
    },
    [setParams],
  );

  const filterValues = useMemo(
    () => ({
      search: params.q,
      category: params.category,
      sortBy: params.sortBy,
      direction: params.direction,
    }),
    [params.q, params.category, params.sortBy, params.direction],
  );

  const total = query.data?.totalElements ?? 0;

  return (
    <div>
      <PageHeader
        title={term ? `Results for “${term}”` : 'Search'}
        description={
          term && !query.isLoading
            ? `${total} ${total === 1 ? 'product' : 'products'} found`
            : 'Search the catalogue by product name or SKU.'
        }
        breadcrumbs={[{ label: 'Home', to: paths.home }, { label: 'Search' }]}
      />

      <div className="space-y-6">
        <ProductFilters
          values={filterValues}
          onChange={handleFilterChange}
          onReset={resetParams}
        />

        <ProductGrid
          products={query.data?.content}
          isLoading={query.isLoading}
          error={query.error ? normalizeError(query.error) : null}
          onRetry={() => void query.refetch()}
          skeletonCount={params.size}
          emptyTitle={term ? `Nothing matches “${term}”` : 'Start typing to search'}
          emptyDescription={
            term
              ? 'Check the spelling, or try a broader term. Search covers product names and SKUs.'
              : 'Search covers product names and SKUs.'
          }
          emptyAction={
            term ? (
              <Button variant="outline" asChild>
                <Link to={paths.products}>
                  <Search aria-hidden />
                  Browse everything instead
                </Link>
              </Button>
            ) : undefined
          }
        />

        <PagePagination data={query.data} onPageChange={(page) => setParams({ page })} />
      </div>
    </div>
  );
}
