import { Package } from 'lucide-react';
import { Suspense, useEffect } from 'react';
import { Link, Outlet, useLocation } from 'react-router-dom';

import { FullPageLoader } from '@/components/common/states';
import { ErrorBoundary } from '@/app/error-boundary';
import { paths } from '@/routes/paths';
import { config } from '@/shared/config';

import { useCartSync } from '@/features/cart/cart-api';

import { SiteHeader } from './components/site-header';

/**
 * Restores scroll position on navigation.
 *
 * A single-page app keeps the scroll offset across route changes by default, so following a link
 * from halfway down a product list lands you halfway down the product page. Browsers do this for
 * free; SPAs have to put it back.
 */
function ScrollToTop() {
  const { pathname } = useLocation();

  useEffect(() => {
    window.scrollTo({ top: 0, behavior: 'instant' as ScrollBehavior });
  }, [pathname]);

  return null;
}

export function CustomerLayout() {
  // Folds the browser basket into the account's, once per sign-in. Mounted here rather than on
  // the basket page: somebody who signs in on a product page and then checks out should not have
  // had to visit the basket for their saved items to appear.
  useCartSync();

  return (
    <div className="flex min-h-full flex-col">
      <ScrollToTop />

      {/* First tab stop on every page — keyboard users should not have to walk the whole nav. */}
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-md focus:bg-primary focus:px-4 focus:py-2 focus:text-primary-foreground"
      >
        Skip to content
      </a>

      <SiteHeader />

      <main id="main" className="container flex-1 py-8">
        {/* Scoped per route: a crash in one page must not take the header and nav with it. */}
        <ErrorBoundary>
          <Suspense fallback={<FullPageLoader />}>
            <Outlet />
          </Suspense>
        </ErrorBoundary>
      </main>

      <SiteFooter />
    </div>
  );
}

function SiteFooter() {
  return (
    <footer className="border-t bg-muted/30">
      <div className="container flex flex-col gap-6 py-10 sm:flex-row sm:items-start sm:justify-between">
        <div className="max-w-xs">
          <Link to={paths.home} className="flex items-center gap-2 font-semibold">
            <Package className="h-5 w-5 text-primary" aria-hidden />
            {config.appName}
          </Link>
          <p className="mt-3 text-sm text-muted-foreground">
            Order processing built on an orchestrated Kafka saga. Every order is tracked from
            placement through to confirmation.
          </p>
        </div>

        <div className="grid gap-x-10 gap-y-6 sm:grid-cols-2">
          <nav className="grid gap-y-2 text-sm" aria-label="Shop">
            <p className="font-medium">Shop</p>
            <FooterLink to={paths.products}>All products</FooterLink>
            <FooterLink to={paths.categories}>Categories</FooterLink>
            <FooterLink to={paths.orders}>My orders</FooterLink>
            <FooterLink to={paths.wishlist}>Wishlist</FooterLink>
            <FooterLink to={paths.cart}>Basket</FooterLink>
          </nav>

          {/*
            Reachable from every page, which is the point: a shopper has to be able to find the
            returns policy and who they are buying from before they decide to buy, not after.
          */}
          <nav className="grid gap-y-2 text-sm" aria-label="Legal">
            <p className="font-medium">Buying from us</p>
            <FooterLink to={paths.returns}>Returns and refunds</FooterLink>
            <FooterLink to={paths.terms}>Terms of sale</FooterLink>
            <FooterLink to={paths.privacy}>Privacy</FooterLink>
            <FooterLink to={paths.contact}>Contact</FooterLink>
          </nav>
        </div>
      </div>

      <div className="border-t">
        <p className="container py-4 text-xs text-muted-foreground">
          © {new Date().getFullYear()} {config.appName}. A reference implementation.
        </p>
      </div>
    </footer>
  );
}

function FooterLink({ to, children }: { to: string; children: React.ReactNode }) {
  return (
    <Link to={to} className="text-muted-foreground transition-colors hover:text-foreground">
      {children}
    </Link>
  );
}
