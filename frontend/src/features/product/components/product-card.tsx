import { Heart, ImageOff, ShoppingCart, Star } from 'lucide-react';
import { memo } from 'react';
import { Link } from 'react-router-dom';
import { toast } from 'sonner';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { useCartActions } from '@/features/cart/hooks';
import { useWishlistView } from '@/features/wishlist/use-wishlist';
import { paths } from '@/routes/paths';
import { cn } from '@/utils/cn';
import { formatMoney } from '@/utils/format';

import { stockLevel, type Product } from '../types';

interface ProductCardProps {
  product: Product;
  className?: string;
}

function ProductCardImpl({ product, className }: ProductCardProps) {
  const { add: addToCart } = useCartActions();
  const wishlist = useWishlistView();
  const isWishlisted = wishlist.has(product.id);

  const level = stockLevel(product);
  const soldOut = level === 'out';

  return (
    <Card
      className={cn(
        'group relative flex flex-col overflow-hidden transition-shadow hover:shadow-md',
        className,
      )}
    >
      <button
        type="button"
        onClick={() => {
          wishlist.toggle(product);
          toast.success(isWishlisted ? 'Removed from your wishlist' : 'Saved to your wishlist');
        }}
        className="absolute right-2 top-2 z-10 rounded-full bg-background/90 p-2 shadow-sm transition-colors hover:bg-background"
        aria-label={isWishlisted ? 'Remove from wishlist' : 'Save to wishlist'}
        aria-pressed={isWishlisted}
      >
        <Heart
          className={cn('h-4 w-4', isWishlisted ? 'fill-destructive text-destructive' : 'text-muted-foreground')}
          aria-hidden
        />
      </button>

      {/* The whole tile links to the product; the buttons above and below stop propagation
          by sitting outside the anchor rather than inside it — nested interactive elements
          are invalid markup and break keyboard navigation. */}
      <Link to={paths.product(product.id)} className="flex flex-1 flex-col">
        <div className="relative aspect-square overflow-hidden bg-muted">
          {product.imageUrl ? (
            <img
              src={product.imageUrl}
              alt={product.name}
              loading="lazy"
              decoding="async"
              className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-105"
            />
          ) : (
            <div className="flex h-full items-center justify-center text-muted-foreground">
              <ImageOff className="h-8 w-8" aria-hidden />
            </div>
          )}

          {soldOut ? (
            <div className="absolute inset-0 flex items-center justify-center bg-background/70">
              <Badge variant="secondary">Out of stock</Badge>
            </div>
          ) : level === 'low' ? (
            <Badge variant="warning" className="absolute bottom-2 left-2">
              Only {product.availableQuantity} left
            </Badge>
          ) : null}
        </div>

        <div className="flex flex-1 flex-col p-4">
          {product.ratingCount > 0 && product.ratingAverage !== null ? (
            <span
              className="flex items-center gap-1 text-xs text-muted-foreground"
              aria-label={`Rated ${product.ratingAverage} out of 5 from ${product.ratingCount} reviews`}
            >
              <Star className="h-3.5 w-3.5 fill-amber-400 text-amber-400" aria-hidden />
              <span className="tabular">{product.ratingAverage.toFixed(1)}</span>
              <span>({product.ratingCount})</span>
            </span>
          ) : null}

          {product.category ? (
            <p className="text-xs uppercase tracking-wide text-muted-foreground">
              {product.category}
            </p>
          ) : null}

          <h3 className="mt-1 line-clamp-2 text-sm font-medium leading-snug">{product.name}</h3>

          <p className="mt-auto pt-3 text-base font-semibold tabular">
            {formatMoney(product.price, product.currency)}
          </p>
        </div>
      </Link>

      <div className="px-4 pb-4">
        <Button
          className="w-full"
          size="sm"
          disabled={soldOut}
          onClick={() => {
            addToCart(product, 1);
            toast.success(`${product.name} added to your basket`);
          }}
        >
          <ShoppingCart aria-hidden />
          {soldOut ? 'Out of stock' : 'Add to basket'}
        </Button>
      </div>
    </Card>
  );
}

/**
 * Memoised: a grid renders 12–24 of these, and the parent re-renders on every filter keystroke,
 * pagination click and cart mutation. Without this each of those re-renders the whole grid.
 */
export const ProductCard = memo(ProductCardImpl);

export function ProductCardSkeleton() {
  return (
    <Card className="overflow-hidden">
      <Skeleton className="aspect-square rounded-none" />
      <div className="space-y-2 p-4">
        <Skeleton className="h-3 w-16" />
        <Skeleton className="h-4 w-full" />
        <Skeleton className="h-4 w-2/3" />
        <Skeleton className="h-5 w-20" />
      </div>
      <div className="px-4 pb-4">
        <Skeleton className="h-9 w-full" />
      </div>
    </Card>
  );
}
