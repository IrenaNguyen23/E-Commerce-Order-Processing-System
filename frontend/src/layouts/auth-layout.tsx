import { Package, ShieldCheck, Truck, Zap } from 'lucide-react';
import { Suspense } from 'react';
import { Link, Outlet } from 'react-router-dom';

import { ErrorBoundary } from '@/app/error-boundary';
import { FullPageLoader } from '@/components/common/states';
import { paths } from '@/routes/paths';
import { config } from '@/shared/config';

const HIGHLIGHTS = [
  {
    icon: Zap,
    title: 'Order in seconds',
    description: 'Checkout is one screen. No account wizard, no upsells.',
  },
  {
    icon: Truck,
    title: 'Live order tracking',
    description: 'Watch stock reservation, payment and confirmation happen in real time.',
  },
  {
    icon: ShieldCheck,
    title: 'Nothing charged twice',
    description: 'Every order carries an idempotency key. A retry never creates a second one.',
  },
];

/**
 * Shell for login, register and password screens.
 *
 * Two columns on desktop, form-only on mobile — the marketing panel is `hidden lg:flex` rather
 * than stacked, because nobody scrolls past three feature bullets to reach a login form.
 */
export function AuthLayout() {
  return (
    <div className="grid min-h-full lg:grid-cols-2">
      <div className="flex flex-col justify-center px-6 py-12 sm:px-12">
        <div className="mx-auto w-full max-w-sm">
          <Link to={paths.home} className="mb-8 flex items-center gap-2 font-semibold">
            <Package className="h-5 w-5 text-primary" aria-hidden />
            {config.appName}
          </Link>

          <ErrorBoundary>
            <Suspense fallback={<FullPageLoader />}>
              <Outlet />
            </Suspense>
          </ErrorBoundary>
        </div>
      </div>

      <aside className="hidden flex-col justify-center bg-muted/40 px-12 lg:flex">
        <div className="mx-auto max-w-md">
          <h2 className="text-2xl font-semibold tracking-tight">
            Order processing that shows its work
          </h2>
          <p className="mt-3 text-sm text-muted-foreground">
            Six services agree on every order without a distributed transaction. When something
            fails, it is rolled back cleanly — and you are told exactly where and why.
          </p>

          <ul className="mt-10 space-y-6">
            {HIGHLIGHTS.map((item) => (
              <li key={item.title} className="flex gap-4">
                <span
                  className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-background shadow-sm"
                  aria-hidden
                >
                  <item.icon className="h-5 w-5 text-primary" />
                </span>
                <div>
                  <p className="text-sm font-medium">{item.title}</p>
                  <p className="mt-0.5 text-sm text-muted-foreground">{item.description}</p>
                </div>
              </li>
            ))}
          </ul>
        </div>
      </aside>
    </div>
  );
}
