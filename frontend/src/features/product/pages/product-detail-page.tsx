import { Heart, ImageOff, PackageCheck, ShoppingCart, Truck } from 'lucide-react';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { toast } from 'sonner';

import { PageHeader } from '@/components/common/page-header';
import { ErrorState } from '@/components/common/states';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Separator } from '@/components/ui/separator';
import { Skeleton } from '@/components/ui/skeleton';
import { useCartActions } from '@/features/cart/hooks';

import { ProductReviews } from '../components/product-reviews';
import { useWishlistView } from '@/features/wishlist/use-wishlist';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { cn } from '@/utils/cn';
import { formatMoney } from '@/utils/format';

import { ProductGrid } from '../components/product-grid';
import { QuantityStepper } from '../components/quantity-stepper';
import { useProduct, useProducts } from '../hooks';
import { stockLevel } from '../types';

export default function ProductDetailPage() {
  const { productId } = useParams<{ productId: string }>();
  const [quantity, setQuantity] = useState(1);

  const query = useProduct(productId);
  const { add: addToCart } = useCartActions();
  const wishlist = useWishlistView();
  const isWishlisted = productId ? wishlist.has(productId) : false;

  const product = query.data;

  // Same category, minus the product being viewed. Only fetched once the category is known.
  const related = useProducts({
    page: 0,
    size: 5,
    // By slug: the filter keys on the stable identifier, not on the label.
    category: product?.categorySlug ?? undefined,
    activeOnly: true,
  });

  if (query.isError) {
    return (
      <ErrorState
        error={normalizeError(query.error)}
        onRetry={() => void query.refetch()}
        title="Could not load this product"
      />
    );
  }

  if (query.isLoading || !product) {
    return <ProductDetailSkeleton />;
  }

  const level = stockLevel(product);
  const soldOut = level === 'out';

  return (
    <div className="space-y-16">
      <PageHeader
        title={product.name}
        breadcrumbs={[
          { label: 'Home', to: paths.home },
          { label: 'Shop', to: paths.products },
          ...(product.category
            ? [{ label: product.category, to: paths.category(product.category) }]
            : []),
          { label: product.name },
        ]}
        className="mb-0"
      />

      <div className="grid gap-10 lg:grid-cols-2">
        <div className="aspect-square overflow-hidden rounded-lg border bg-muted">
          {product.imageUrl ? (
            <img
              src={product.imageUrl}
              alt={product.name}
              className="h-full w-full object-cover"
              // Above the fold on this page — eager, unlike the grid tiles.
              loading="eager"
            />
          ) : (
            <div className="flex h-full items-center justify-center text-muted-foreground">
              <ImageOff className="h-12 w-12" aria-hidden />
            </div>
          )}
        </div>

        <div>
          {product.category ? (
            <Link
              to={paths.category(product.category)}
              className="text-sm uppercase tracking-wide text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
            >
              {product.category}
            </Link>
          ) : null}

          <h2 className="mt-2 text-2xl font-semibold tracking-tight">{product.name}</h2>
          <p className="mt-1 font-mono text-xs text-muted-foreground">SKU {product.sku}</p>

          <p className="mt-6 text-3xl font-semibold tabular">
            {formatMoney(product.price, product.currency)}
          </p>

          <div className="mt-4">
            {soldOut ? (
              <Badge variant="secondary">Out of stock</Badge>
            ) : level === 'low' ? (
              <Badge variant="warning">Only {product.availableQuantity} left</Badge>
            ) : (
              <Badge variant="success" className="gap-1.5">
                <PackageCheck className="h-3 w-3" aria-hidden />
                In stock
              </Badge>
            )}
          </div>

          {product.description ? (
            <p className="mt-6 text-sm leading-relaxed text-muted-foreground">
              {product.description}
            </p>
          ) : null}

          <Separator className="my-6" />

          <div className="flex flex-wrap items-center gap-3">
            <QuantityStepper
              value={quantity}
              onChange={setQuantity}
              max={Math.max(1, product.availableQuantity)}
              disabled={soldOut}
            />

            <Button
              size="lg"
              className="flex-1 sm:flex-none"
              disabled={soldOut}
              onClick={() => {
                addToCart(product, quantity);
                toast.success(
                  `${quantity} × ${product.name} added to your basket`,
                );
              }}
            >
              <ShoppingCart aria-hidden />
              {soldOut ? 'Out of stock' : 'Add to basket'}
            </Button>

            <Button
              size="lg"
              variant="outline"
              onClick={() => {
                wishlist.toggle(product);
                toast.success(
                  isWishlisted ? 'Removed from your wishlist' : 'Saved to your wishlist',
                );
              }}
              aria-pressed={isWishlisted}
            >
              <Heart
                className={cn(isWishlisted && 'fill-destructive text-destructive')}
                aria-hidden
              />
              <span className="sr-only sm:not-sr-only">
                {isWishlisted ? 'Saved' : 'Save'}
              </span>
            </Button>
          </div>

          <div className="mt-6 flex items-start gap-3 rounded-lg border bg-muted/40 p-4">
            <Truck className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" aria-hidden />
            <p className="text-sm text-muted-foreground">
              Stock is reserved the moment you order. If payment fails, the reservation is
              released automatically and you are not charged.
            </p>
          </div>
        </div>
      </div>

      <div className="my-12 border-t pt-10">
        <ProductReviews productId={product.id} />
      </div>

      {product.category ? (
        <section>
          <h2 className="mb-5 text-xl font-semibold tracking-tight">More in {product.category}</h2>
          <ProductGrid
            products={related.data?.content.filter((item) => item.id !== product.id).slice(0, 4)}
            isLoading={related.isLoading}
            skeletonCount={4}
            emptyTitle="Nothing else here yet"
            emptyDescription="This is the only product in the category so far."
          />
        </section>
      ) : null}
    </div>
  );
}

function ProductDetailSkeleton() {
  return (
    <div className="grid gap-10 lg:grid-cols-2">
      <Skeleton className="aspect-square rounded-lg" />
      <div className="space-y-4">
        <Skeleton className="h-3 w-24" />
        <Skeleton className="h-8 w-3/4" />
        <Skeleton className="h-3 w-32" />
        <Skeleton className="h-10 w-40" />
        <Skeleton className="h-5 w-24" />
        <Skeleton className="h-20 w-full" />
        <Skeleton className="h-12 w-full" />
      </div>
    </div>
  );
}
