import {
  BarChart3,
  Boxes,
  CreditCard,
  ScrollText,
  LayoutDashboard,
  MessageSquare,
  Package,
  PackageCheck,
  RotateCcw,
  ShoppingBag,
  Store,
  Tags,
  Ticket,
  Users,
  Warehouse,
} from 'lucide-react';
import { Suspense, useState } from 'react';
import { Link, NavLink, Outlet } from 'react-router-dom';

import { ErrorBoundary } from '@/app/error-boundary';
import { FullPageLoader } from '@/components/common/states';
import { Button } from '@/components/ui/button';
import { Separator } from '@/components/ui/separator';
import { paths } from '@/routes/paths';
import { config } from '@/shared/config';
import { cn } from '@/utils/cn';

interface NavItem {
  to: string;
  label: string;
  icon: typeof LayoutDashboard;
  /**
   * Set when a screen has no backend behind it — see `ServiceUnavailable`.
   *
   * <p>Nothing uses it at the moment: every screen in this console now has a service. The flag and
   * the component are kept because the pattern is the right answer the next time a gap appears,
   * and because a greyed-out item that explains what is missing beats an item that is simply not
   * there.
   */
  unavailable?: boolean;
  end?: boolean;
}

/**
 * Admin navigation.
 *
 * <p>Every item here reaches a working screen. When that stops being true, mark the item
 * `unavailable` rather than hiding it: the console should reflect the whole intended surface, and
 * a screen that names the endpoints it is waiting for is a specification. Hiding the gap makes it
 * invisible instead of actionable.
 */
const NAV_SECTIONS: Array<{ title: string; items: NavItem[] }> = [
  {
    title: 'Overview',
    items: [
      { to: paths.admin.dashboard, label: 'Dashboard', icon: LayoutDashboard, end: true },
      { to: paths.admin.reports, label: 'Reports', icon: BarChart3 },
    ],
  },
  {
    title: 'Catalogue',
    items: [
      { to: paths.admin.products, label: 'Products', icon: Package },
      { to: paths.admin.inventory, label: 'Inventory', icon: Boxes },
      { to: paths.admin.warehouses, label: 'Warehouses', icon: Warehouse },
      { to: paths.admin.categories, label: 'Categories', icon: Tags },
    ],
  },
  {
    title: 'Commerce',
    items: [
      { to: paths.admin.orders, label: 'Orders', icon: ShoppingBag },
      { to: paths.admin.shipments, label: 'Fulfilment', icon: PackageCheck },
      { to: paths.admin.returns, label: 'Returns', icon: RotateCcw },
      { to: paths.admin.payments, label: 'Payments', icon: CreditCard },
      { to: paths.admin.coupons, label: 'Coupons', icon: Ticket },
      { to: paths.admin.reviews, label: 'Reviews', icon: MessageSquare },
    ],
  },
  {
    title: 'People',
    items: [{ to: paths.admin.users, label: 'Users', icon: Users }],
  },
  {
    title: 'Oversight',
    items: [{ to: paths.admin.audit, label: 'Audit trail', icon: ScrollText }],
  },
];

export function AdminLayout() {
  const [sidebarOpen, setSidebarOpen] = useState(false);

  return (
    <div className="flex min-h-full flex-col lg:flex-row">
      {/* Mobile bar. The sidebar collapses rather than shrinking — a 200 px nav column is
          unusable on a phone, and admin tables need every pixel of width. */}
      <div className="flex items-center gap-3 border-b px-4 py-3 lg:hidden">
        <Button
          variant="outline"
          size="sm"
          onClick={() => setSidebarOpen((open) => !open)}
          aria-expanded={sidebarOpen}
        >
          Menu
        </Button>
        <span className="font-semibold">{config.appName} admin</span>
      </div>

      <aside
        className={cn(
          'w-full shrink-0 border-b bg-muted/30 lg:sticky lg:top-0 lg:h-screen lg:w-64 lg:border-b-0 lg:border-r',
          !sidebarOpen && 'hidden lg:block',
        )}
      >
        <div className="flex h-full flex-col overflow-y-auto p-4">
          <Link
            to={paths.admin.dashboard}
            className="mb-6 hidden items-center gap-2 font-semibold lg:flex"
          >
            <Package className="h-5 w-5 text-primary" aria-hidden />
            {config.appName}
          </Link>

          <nav className="flex-1 space-y-6" aria-label="Admin">
            {NAV_SECTIONS.map((section) => (
              <div key={section.title}>
                <p className="mb-2 px-3 text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  {section.title}
                </p>
                <div className="space-y-0.5">
                  {section.items.map((item) => (
                    <NavLink
                      key={item.to}
                      to={item.to}
                      end={item.end}
                      onClick={() => setSidebarOpen(false)}
                      className={({ isActive }) =>
                        cn(
                          'flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors',
                          isActive
                            ? 'bg-primary text-primary-foreground'
                            : 'text-muted-foreground hover:bg-accent hover:text-accent-foreground',
                          item.unavailable && !isActive && 'opacity-60',
                        )
                      }
                    >
                      <item.icon className="h-4 w-4 shrink-0" aria-hidden />
                      <span className="truncate">{item.label}</span>
                    </NavLink>
                  ))}
                </div>
              </div>
            ))}
          </nav>

          <Separator className="my-4" />

          <Button variant="outline" size="sm" asChild>
            <Link to={paths.home}>
              <Store aria-hidden />
              Back to the shop
            </Link>
          </Button>
        </div>
      </aside>

      <main className="min-w-0 flex-1 p-4 lg:p-8">
        <ErrorBoundary>
          <Suspense fallback={<FullPageLoader />}>
            <Outlet />
          </Suspense>
        </ErrorBoundary>
      </main>
    </div>
  );
}
