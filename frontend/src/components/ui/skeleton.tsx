import type * as React from 'react';

import { cn } from '@/utils/cn';

/**
 * A shimmering placeholder sized like the content it stands in for.
 *
 * Skeletons rather than spinners throughout: they hold the layout, so nothing jumps when data
 * arrives, and they communicate the shape of what is loading.
 */
function Skeleton({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn('animate-pulse rounded-md bg-muted', className)}
      aria-hidden
      {...props}
    />
  );
}

export { Skeleton };
