import { Construction, Server } from 'lucide-react';
import { Link } from 'react-router-dom';

import { PageHeader } from '@/components/common/page-header';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { paths } from '@/routes/paths';

export interface MissingEndpoint {
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  path: string;
  purpose: string;
}

/**
 * A screen for a feature whose backend does not exist yet.
 *
 * <p><b>Currently unused</b> — every screen in the admin console now has a service behind it. Kept
 * because the pattern is the right answer the next time a gap appears: it names the exact
 * endpoints the feature needs, so the screen doubles as a specification, and it mocks nothing. A
 * console that appears to manage something and silently does nothing is worse than one that admits
 * the gap.
 */
interface ServiceUnavailableProps {
  title: string;
  /** What the screen would do, written as if it already existed. */
  summary: string;
  /** The backend service that would own it, e.g. "User Service". */
  service: string;
  endpoints: MissingEndpoint[];
  /** Anything that *is* possible today, so the user is not left with a dead end. */
  workaround?: { text: string; to?: string; label?: string };
  breadcrumbs?: { label: string; to?: string }[];
}

/**
 * The screen for a feature the backend cannot support yet.
 *
 * This exists because the alternative is worse. A page wired to a non-existent endpoint either
 * throws a confusing 404 or — far worse — is mocked to look like it works, and someone ships on
 * that assumption. Naming the exact endpoints turns a dead end into a backlog item, and keeps the
 * gap visible in the product rather than buried in a document.
 *
 * Every one of these disappears the moment its endpoint lands: delete the route entry, point it
 * at a real page.
 */
export function ServiceUnavailable({
  title,
  summary,
  service,
  endpoints,
  workaround,
  breadcrumbs,
}: ServiceUnavailableProps) {
  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader title={title} breadcrumbs={breadcrumbs} />

      <Card>
        <CardHeader className="flex-row items-start gap-4 space-y-0">
          <span
            className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-warning/15 text-warning"
            aria-hidden
          >
            <Construction className="h-5 w-5" />
          </span>
          <div>
            <CardTitle>Not available yet</CardTitle>
            <p className="mt-1 text-sm text-muted-foreground">{summary}</p>
          </div>
        </CardHeader>

        <CardContent className="space-y-6">
          <div className="rounded-lg border bg-muted/40 p-4">
            <p className="flex items-center gap-2 text-sm font-medium">
              <Server className="h-4 w-4 text-muted-foreground" aria-hidden />
              Waiting on {service}
            </p>
            <p className="mt-1 text-sm text-muted-foreground">
              The frontend for this screen is ready. It needs the following endpoints before it
              can do anything real — and it will not pretend to work until they exist.
            </p>

            <ul className="mt-4 space-y-2">
              {endpoints.map((endpoint) => (
                <li
                  key={`${endpoint.method} ${endpoint.path}`}
                  className="flex flex-col gap-1 rounded-md bg-background p-3 sm:flex-row sm:items-baseline sm:gap-3"
                >
                  <code className="shrink-0 font-mono text-xs font-semibold">
                    {endpoint.method} {endpoint.path}
                  </code>
                  <span className="text-xs text-muted-foreground">{endpoint.purpose}</span>
                </li>
              ))}
            </ul>
          </div>

          {workaround ? (
            <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
              <p className="text-sm text-muted-foreground">{workaround.text}</p>
              {workaround.to ? (
                <Button variant="outline" asChild className="shrink-0">
                  <Link to={workaround.to}>{workaround.label ?? 'Go there'}</Link>
                </Button>
              ) : null}
            </div>
          ) : null}

          <Button variant="ghost" asChild>
            <Link to={paths.admin.dashboard}>Back to the dashboard</Link>
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
