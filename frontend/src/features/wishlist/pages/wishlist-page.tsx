import { Heart, Trash2 } from 'lucide-react';
import { useMemo } from 'react';
import { Link } from 'react-router-dom';

import { PageHeader } from '@/components/common/page-header';
import { EmptyState } from '@/components/common/states';
import { Button } from '@/components/ui/button';
import { ProductGrid } from '@/features/product/components/product-grid';
import { useProductLookup } from '@/features/product/hooks';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';

import { useWishlistView } from '../use-wishlist';

/**
 * The wishlist.
 *
 * Ids come from local storage, the products themselves from a single batch lookup — so a saved
 * item always shows its current price and stock, not whatever it cost when it was saved.
 * Anything withdrawn from the catalogue simply drops out of the result.
 */
export default function WishlistPage() {
  // Server-backed when signed in, local when not. The page does not care which.
  const { items: entries, clear } = useWishlistView();

  const productIds = useMemo(() => entries.map((entry) => entry.productId), [entries]);
  const query = useProductLookup(productIds, productIds.length > 0);

  // Preserve the saved order (newest first) rather than whatever order the API returned.
  const products = useMemo(() => {
    if (!query.data) return undefined;
    return productIds
      .map((id) => query.data.get(id))
      .filter((product): product is NonNullable<typeof product> => Boolean(product));
  }, [productIds, query.data]);

  if (entries.length === 0) {
    return (
      <div>
        <PageHeader
          title="Wishlist"
          breadcrumbs={[{ label: 'Home', to: paths.home }, { label: 'Wishlist' }]}
        />
        <EmptyState
          icon={<Heart className="h-10 w-10" />}
          title="Nothing saved yet"
          description="Tap the heart on any product to keep it here for later."
          action={
            <Button asChild>
              <Link to={paths.products}>Browse the catalogue</Link>
            </Button>
          }
        />
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title="Wishlist"
        description={`${entries.length} saved ${entries.length === 1 ? 'item' : 'items'}`}
        breadcrumbs={[{ label: 'Home', to: paths.home }, { label: 'Wishlist' }]}
        actions={
          <Button variant="ghost" size="sm" onClick={clear}>
            <Trash2 aria-hidden />
            Clear all
          </Button>
        }
      />

      <ProductGrid
        products={products}
        isLoading={query.isLoading}
        error={query.error ? normalizeError(query.error) : null}
        onRetry={() => void query.refetch()}
        skeletonCount={Math.min(entries.length, 8)}
        emptyTitle="These products are no longer available"
        emptyDescription="Everything you saved has been withdrawn from the catalogue."
      />
    </div>
  );
}
