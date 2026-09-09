import { Home, Search } from 'lucide-react';
import { Link, useLocation } from 'react-router-dom';

import { Button } from '@/components/ui/button';
import { paths } from '@/routes/paths';

export default function NotFoundPage() {
  const location = useLocation();

  return (
    <div className="flex min-h-[50vh] flex-col items-center justify-center text-center">
      <p className="text-6xl font-semibold tracking-tight text-muted-foreground/40">404</p>
      <h1 className="mt-4 text-xl font-semibold">This page does not exist</h1>
      <p className="mt-2 max-w-md text-sm text-muted-foreground">
        Nothing is served at{' '}
        <code className="font-mono text-xs">{location.pathname}</code>. It may have moved, or the
        link may be wrong.
      </p>

      <div className="mt-8 flex flex-wrap justify-center gap-3">
        <Button asChild>
          <Link to={paths.home}>
            <Home aria-hidden />
            Go home
          </Link>
        </Button>
        <Button variant="outline" asChild>
          <Link to={paths.products}>
            <Search aria-hidden />
            Browse the catalogue
          </Link>
        </Button>
      </div>
    </div>
  );
}
