import type { ReactNode } from 'react';

import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Skeleton } from '@/components/ui/skeleton';
import type { AppError } from '@/types/api';
import { cn } from '@/utils/cn';

import { EmptyState, ErrorState } from './states';

export interface Column<T> {
  /** Stable key; also used as the React key for the cell. */
  id: string;
  header: ReactNode;
  cell: (row: T) => ReactNode;
  /** Extra classes for both the header and its cells — width, alignment, responsive hiding. */
  className?: string;
  /** Hidden below `sm`. Use for columns that are useful but not essential on a phone. */
  hideOnMobile?: boolean;
}

interface DataTableProps<T> {
  columns: Column<T>[];
  rows: T[] | undefined;
  getRowId: (row: T) => string;
  isLoading?: boolean;
  error?: AppError | null;
  onRetry?: () => void;
  onRowClick?: (row: T) => void;
  emptyTitle?: string;
  emptyDescription?: string;
  emptyAction?: ReactNode;
  /** Skeleton rows drawn while loading; match the page size so the layout does not jump. */
  skeletonRows?: number;
  className?: string;
}

/**
 * One table component for every admin list.
 *
 * It owns the four states — loading, error, empty, populated — so no page can accidentally
 * render a bare `<table>` with nothing in it and call that done.
 */
export function DataTable<T>({
  columns,
  rows,
  getRowId,
  isLoading = false,
  error = null,
  onRetry,
  onRowClick,
  emptyTitle = 'Nothing to show',
  emptyDescription,
  emptyAction,
  skeletonRows = 8,
  className,
}: DataTableProps<T>) {
  if (error) {
    return <ErrorState error={error} onRetry={onRetry} />;
  }

  if (!isLoading && rows && rows.length === 0) {
    return (
      <EmptyState title={emptyTitle} description={emptyDescription} action={emptyAction} />
    );
  }

  return (
    <div className={cn('rounded-lg border', className)}>
      <Table>
        <TableHeader>
          <TableRow>
            {columns.map((column) => (
              <TableHead
                key={column.id}
                className={cn(column.hideOnMobile && 'hidden sm:table-cell', column.className)}
              >
                {column.header}
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>

        <TableBody>
          {isLoading
            ? Array.from({ length: skeletonRows }, (_, index) => (
                <TableRow key={`skeleton-${index}`}>
                  {columns.map((column) => (
                    <TableCell
                      key={column.id}
                      className={cn(column.hideOnMobile && 'hidden sm:table-cell')}
                    >
                      <Skeleton className="h-4 w-full max-w-[160px]" />
                    </TableCell>
                  ))}
                </TableRow>
              ))
            : rows?.map((row) => (
                <TableRow
                  key={getRowId(row)}
                  onClick={onRowClick ? () => onRowClick(row) : undefined}
                  className={cn(onRowClick && 'cursor-pointer')}
                  // Keyboard parity: a clickable row must be reachable without a mouse.
                  tabIndex={onRowClick ? 0 : undefined}
                  onKeyDown={
                    onRowClick
                      ? (event) => {
                          if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault();
                            onRowClick(row);
                          }
                        }
                      : undefined
                  }
                >
                  {columns.map((column) => (
                    <TableCell
                      key={column.id}
                      className={cn(column.hideOnMobile && 'hidden sm:table-cell', column.className)}
                    >
                      {column.cell(row)}
                    </TableCell>
                  ))}
                </TableRow>
              ))}
        </TableBody>
      </Table>
    </div>
  );
}
