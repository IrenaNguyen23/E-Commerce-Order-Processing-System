import { ArrowRight, PackageCheck, ShieldCheck, Timer } from 'lucide-react';
import { Link } from 'react-router-dom';

import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';

import { ProductGrid } from '../components/product-grid';
import { useCategories, useProducts } from '../hooks';

const VALUE_PROPS = [
  {
    icon: Timer,
    title: 'Watch your order progress',
    description:
      'Stock reservation, payment and confirmation each land in turn — no black box between checkout and the confirmation email.',
  },
  {
    icon: ShieldCheck,
    title: 'Never charged twice',
    description:
      'Every order carries an idempotency key, and payment is guarded by a one-charge-per-order rule in the database itself.',
  },
  {
    icon: PackageCheck,
    title: 'Clean rollbacks',
    description:
      'If payment fails, reserved stock is released automatically and you are told exactly where it stopped.',
  },
];

export default function HomePage() {
  // Newest first, one row on desktop.
  const featured = useProducts({ page: 0, size: 8, activeOnly: true, sortBy: 'createdAt', direction: 'desc' });
  const categories = useCategories();

  return (
    <div className="space-y-16">
      <section className="rounded-2xl bg-muted/40 px-6 py-14 text-center sm:px-12">
        <h1 className="mx-auto max-w-2xl text-3xl font-semibold tracking-tight sm:text-4xl">
          Order with confidence, and see exactly what happens next
        </h1>
        <p className="mx-auto mt-4 max-w-xl text-muted-foreground">
          A working storefront on an orchestrated Kafka saga. Place an order and follow it through
          stock, payment and confirmation in real time.
        </p>
        <div className="mt-8 flex flex-wrap justify-center gap-3">
          <Button size="lg" asChild>
            <Link to={paths.products}>
              Browse the catalogue
              <ArrowRight aria-hidden />
            </Link>
          </Button>
          <Button size="lg" variant="outline" asChild>
            <Link to={paths.categories}>Shop by category</Link>
          </Button>
        </div>
      </section>

      {categories.data && categories.data.length > 0 ? (
        <section>
          <div className="mb-5 flex items-baseline justify-between">
            <h2 className="text-xl font-semibold tracking-tight">Categories</h2>
            <Link
              to={paths.categories}
              className="text-sm text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
            >
              See all
            </Link>
          </div>

          {/* Linked by slug, labelled by name: a bookmarked section survives a rename. */}
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
            {categories.data.slice(0, 8).map((category) => (
              <Link key={category.slug} to={paths.category(category.slug)}>
                <Card className="transition-colors hover:border-primary hover:bg-accent/50">
                  <CardContent className="p-4">
                    <p className="truncate text-sm font-medium">{category.name}</p>
                    <p className="mt-1 text-xs text-muted-foreground tabular">
                      {category.productCount} {category.productCount === 1 ? 'product' : 'products'}
                    </p>
                  </CardContent>
                </Card>
              </Link>
            ))}
          </div>
        </section>
      ) : null}

      <section>
        <div className="mb-5 flex items-baseline justify-between">
          <h2 className="text-xl font-semibold tracking-tight">New in</h2>
          <Link
            to={paths.products}
            className="text-sm text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
          >
            See all
          </Link>
        </div>

        <ProductGrid
          products={featured.data?.content}
          isLoading={featured.isLoading}
          error={featured.error ? normalizeError(featured.error) : null}
          onRetry={() => void featured.refetch()}
          skeletonCount={8}
          emptyTitle="The catalogue is empty"
          emptyDescription="Once products are added they will appear here."
        />
      </section>

      <section className="grid gap-6 sm:grid-cols-3">
        {VALUE_PROPS.map((item) => (
          <div key={item.title}>
            <span
              className="mb-3 flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10 text-primary"
              aria-hidden
            >
              <item.icon className="h-5 w-5" />
            </span>
            <h3 className="text-sm font-medium">{item.title}</h3>
            <p className="mt-1 text-sm text-muted-foreground">{item.description}</p>
          </div>
        ))}
      </section>
    </div>
  );
}
