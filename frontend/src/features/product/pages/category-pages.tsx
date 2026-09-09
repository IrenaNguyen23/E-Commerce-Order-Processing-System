import { Tags } from 'lucide-react';
import { useCallback, useMemo } from 'react';
import { Link, useParams } from 'react-router-dom';

import { PageHeader } from '@/components/common/page-header';
import { PagePagination } from '@/components/common/pagination';
import { EmptyState, ErrorState } from '@/components/common/states';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { useQueryParams } from '@/hooks/use-query-params';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { DEFAULT_PAGE_SIZE } from '@/shared/config';

import { ProductFilters } from '../components/product-filters';
import { ProductGrid } from '../components/product-grid';
import { useCategories, useProducts } from '../hooks';

/**
 * Category browsing.
 *
 * There is no category entity in Inventory Service — `category` is a string column on the
 * product. So the index is derived from the catalogue, and a "category page" is simply the
 * product list scoped to one value. That is not a shortcut; it is what the data model supports,
 * and it behaves identically to a real category endpoint from the user's side.
 */

export function CategoryIndexPage() {
  const query = useCategories();

  return (
    <div>
      <PageHeader
        title="Categories"
        description="Browse the catalogue by category."
        breadcrumbs={[{ label: 'Home', to: paths.home }, { label: 'Categories' }]}
      />

      {query.isError ? (
        <ErrorState error={normalizeError(query.error)} onRetry={() => void query.refetch()} />
      ) : query.isLoading ? (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
          {Array.from({ length: 8 }, (_, index) => (
            <Skeleton key={index} className="h-20 rounded-lg" />
          ))}
        </div>
      ) : !query.data || query.data.length === 0 ? (
        <EmptyState
          icon={<Tags className="h-10 w-10" />}
          title="No categories yet"
          description="Categories appear as soon as products are added with one set."
        />
      ) : (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
          {/* Linked by slug, labelled by name: a bookmarked section survives a rename. */}
          {query.data.map((category) => (
            <Link key={category.slug} to={paths.category(category.slug)}>
              <Card className="h-full transition-colors hover:border-primary hover:bg-accent/50">
                <CardContent className="p-4">
                  <p className="truncate text-sm font-medium">{category.name}</p>
                  <p className="mt-1 text-xs text-muted-foreground tabular">
                    {category.productCount} {category.productCount === 1 ? 'product' : 'products'}
                  </p>
                </CardContent>
              </Card>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}

const DEFAULTS = {
  page: 0,
  size: DEFAULT_PAGE_SIZE,
  search: '',
  sortBy: 'name',
  direction: 'asc' as 'asc' | 'desc',
};

export function CategoryPage() {
  const { categoryName } = useParams<{ categoryName: string }>();
  const category = categoryName ? decodeURIComponent(categoryName) : '';

  const { params, setParams, resetParams } = useQueryParams(DEFAULTS);

  const query = useProducts({
    page: params.page,
    size: params.size,
    category,
    search: params.search || undefined,
    activeOnly: true,
    sortBy: params.sortBy,
    direction: params.direction,
  });

  const handleFilterChange = useCallback(
    (patch: Partial<typeof DEFAULTS>) => setParams({ ...patch, page: 0 }),
    [setParams],
  );

  const filterValues = useMemo(
    () => ({
      search: params.search,
      category: '',
      sortBy: params.sortBy,
      direction: params.direction,
    }),
    [params.search, params.sortBy, params.direction],
  );

  return (
    <div>
      <PageHeader
        title={category}
        description="Everything in this category."
        breadcrumbs={[
          { label: 'Home', to: paths.home },
          { label: 'Categories', to: paths.categories },
          { label: category },
        ]}
      />

      <div className="space-y-6">
        {/* The category picker is hidden: this page is already scoped to one. */}
        <ProductFilters
          values={filterValues}
          onChange={handleFilterChange}
          onReset={resetParams}
          showCategory={false}
          totalElements={query.data?.totalElements}
        />

        <ProductGrid
          products={query.data?.content}
          isLoading={query.isLoading}
          error={query.error ? normalizeError(query.error) : null}
          onRetry={() => void query.refetch()}
          skeletonCount={params.size}
          emptyTitle={`Nothing in ${category} right now`}
          emptyDescription="Try another category, or browse the whole catalogue."
        />

        <PagePagination data={query.data} onPageChange={(page) => setParams({ page })} />
      </div>
    </div>
  );
}

export default CategoryPage;
