import { ChevronRight } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';

import { cn } from '@/utils/cn';

export interface Crumb {
  label: string;
  /** Omit on the current page — the last crumb is never a link. */
  to?: string;
}

interface PageHeaderProps {
  title: string;
  description?: string;
  breadcrumbs?: Crumb[];
  actions?: ReactNode;
  className?: string;
}

/**
 * The heading block every page starts with.
 *
 * Shared so the `h1`, spacing and breadcrumb markup stay identical — which matters for screen
 * readers as much as for looks: exactly one `h1` per page, always in the same place.
 */
export function PageHeader({
  title,
  description,
  breadcrumbs,
  actions,
  className,
}: PageHeaderProps) {
  return (
    <div className={cn('mb-6 space-y-3', className)}>
      {breadcrumbs && breadcrumbs.length > 0 ? (
        <nav aria-label="Breadcrumb">
          <ol className="flex flex-wrap items-center gap-1 text-sm text-muted-foreground">
            {breadcrumbs.map((crumb, index) => {
              const isLast = index === breadcrumbs.length - 1;
              return (
                <li key={`${crumb.label}-${index}`} className="flex items-center gap-1">
                  {crumb.to && !isLast ? (
                    <Link to={crumb.to} className="transition-colors hover:text-foreground">
                      {crumb.label}
                    </Link>
                  ) : (
                    <span className={cn(isLast && 'text-foreground')} aria-current={isLast ? 'page' : undefined}>
                      {crumb.label}
                    </span>
                  )}
                  {!isLast ? <ChevronRight className="h-3.5 w-3.5" aria-hidden /> : null}
                </li>
              );
            })}
          </ol>
        </nav>
      ) : null}

      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="min-w-0">
          <h1 className="truncate text-2xl font-semibold tracking-tight">{title}</h1>
          {description ? (
            <p className="mt-1 text-sm text-muted-foreground">{description}</p>
          ) : null}
        </div>
        {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
      </div>
    </div>
  );
}
