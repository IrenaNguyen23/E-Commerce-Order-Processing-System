import { PackageSearch } from 'lucide-react';
import type { ReactNode } from 'react';

import { EmptyState, ErrorState } from '@/components/common/states';
import type { AppError } from '@/types/api';
import { cn } from '@/utils/cn';

import type { Product } from '../types';
import { ProductCard, ProductCardSkeleton } from './product-card';

interface ProductGridProps {
  products: Product[] | undefined;
  isLoading?: boolean;
  error?: AppError | null;
  onRetry?: () => void;
  emptyTitle?: string;
  emptyDescription?: string;
  emptyAction?: ReactNode;
  /** Match the requested page size so the skeleton occupies the same space as the result. */
  skeletonCount?: number;
  className?: string;
}

/**
 * The catalogue grid, with all four states handled in one place.
 *
 * Every screen that lists products — home, listing, search, category, wishlist — renders through
 * this, so the empty and error cases cannot be skipped by accident on one of them.
 */
export function ProductGrid({
  products,
  isLoading = false,
  error = null,
  onRetry,
  emptyTitle = 'No products found',
  emptyDescription = 'Try removing a filter or searching for something else.',
  emptyAction,
  skeletonCount = 12,
  className,
}: ProductGridProps) {
  if (error) {
    return <ErrorState error={error} onRetry={onRetry} />;
  }

  if (isLoading) {
    return (
      <div className={cn(gridClasses, className)}>
        {Array.from({ length: skeletonCount }, (_, index) => (
          <ProductCardSkeleton key={index} />
        ))}
      </div>
    );
  }

  if (!products || products.length === 0) {
    return (
      <EmptyState
        icon={<PackageSearch className="h-10 w-10" />}
        title={emptyTitle}
        description={emptyDescription}
        action={emptyAction}
      />
    );
  }

  return (
    <div className={cn(gridClasses, className)}>
      {products.map((product) => (
        <ProductCard key={product.id} product={product} />
      ))}
    </div>
  );
}

/**
 * Two columns on a phone rather than one: product tiles are square, and a single column means
 * one item fills the viewport and browsing becomes a scroll marathon.
 */
const gridClasses = 'grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4';
