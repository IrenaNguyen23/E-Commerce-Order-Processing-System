import { ChevronLeft, ChevronRight } from 'lucide-react';
import { memo } from 'react';

import { Button } from '@/components/ui/button';
import type { PageResponse } from '@/types/api';
import { cn } from '@/utils/cn';

interface PaginationProps {
  /** Zero-based, matching the backend. */
  page: number;
  totalPages: number;
  totalElements: number;
  pageSize: number;
  onPageChange: (page: number) => void;
  className?: string;
}

/**
 * Builds a compact page list with ellipses: `1 … 4 5 6 … 20`.
 *
 * Rendering every page number breaks down past ~10 pages, and an admin order list can have
 * hundreds. `-1` marks a gap.
 */
function buildPageList(current: number, total: number): number[] {
  if (total <= 7) return Array.from({ length: total }, (_, index) => index);

  const pages: number[] = [0];
  const start = Math.max(1, current - 1);
  const end = Math.min(total - 2, current + 1);

  if (start > 1) pages.push(-1);
  for (let page = start; page <= end; page += 1) pages.push(page);
  if (end < total - 2) pages.push(-1);

  pages.push(total - 1);
  return pages;
}

function PaginationImpl({
  page,
  totalPages,
  totalElements,
  pageSize,
  onPageChange,
  className,
}: PaginationProps) {
  if (totalPages <= 1) return null;

  const firstItem = page * pageSize + 1;
  const lastItem = Math.min((page + 1) * pageSize, totalElements);

  return (
    <nav
      className={cn('flex flex-col items-center justify-between gap-3 sm:flex-row', className)}
      aria-label="Pagination"
    >
      <p className="text-sm text-muted-foreground tabular">
        Showing <span className="font-medium text-foreground">{firstItem}</span>–
        <span className="font-medium text-foreground">{lastItem}</span> of{' '}
        <span className="font-medium text-foreground">{totalElements}</span>
      </p>

      <div className="flex items-center gap-1">
        <Button
          variant="outline"
          size="icon"
          onClick={() => onPageChange(page - 1)}
          disabled={page === 0}
          aria-label="Previous page"
        >
          <ChevronLeft aria-hidden />
        </Button>

        {buildPageList(page, totalPages).map((candidate, index) =>
          candidate === -1 ? (
            <span
              key={`gap-${index}`}
              className="px-2 text-sm text-muted-foreground"
              aria-hidden
            >
              …
            </span>
          ) : (
            <Button
              key={candidate}
              variant={candidate === page ? 'default' : 'outline'}
              size="icon"
              onClick={() => onPageChange(candidate)}
              aria-label={`Page ${candidate + 1}`}
              aria-current={candidate === page ? 'page' : undefined}
              className="tabular"
            >
              {candidate + 1}
            </Button>
          ),
        )}

        <Button
          variant="outline"
          size="icon"
          onClick={() => onPageChange(page + 1)}
          disabled={page >= totalPages - 1}
          aria-label="Next page"
        >
          <ChevronRight aria-hidden />
        </Button>
      </div>
    </nav>
  );
}

/** Memoised: it re-renders only when the page actually moves, not on every parent state change. */
export const Pagination = memo(PaginationImpl);

/** Convenience wrapper that reads the numbers straight off a `PageResponse`. */
export function PagePagination<T>({
  data,
  onPageChange,
  className,
}: {
  data: PageResponse<T> | undefined;
  onPageChange: (page: number) => void;
  className?: string;
}) {
  if (!data) return null;
  return (
    <Pagination
      page={data.page}
      totalPages={data.totalPages}
      totalElements={data.totalElements}
      pageSize={data.size}
      onPageChange={onPageChange}
      className={className}
    />
  );
}
