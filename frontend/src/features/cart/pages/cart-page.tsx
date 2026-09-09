import { AlertTriangle, ImageOff, ShoppingBag, Trash2 } from 'lucide-react';
import { Link } from 'react-router-dom';

import { PageHeader } from '@/components/common/page-header';
import { EmptyState, ErrorState } from '@/components/common/states';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Separator } from '@/components/ui/separator';
import { Skeleton } from '@/components/ui/skeleton';
import { QuantityStepper } from '@/features/product/components/quantity-stepper';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { cn } from '@/utils/cn';
import { formatMoney } from '@/utils/format';

import { useCartActions, useCartProducts, type PricedCartLine } from '../hooks';

export default function CartPage() {
  const cart = useCartProducts();
  // Through the actions hook rather than the store, so the account's copy follows. Writing to
  // the store directly leaves somebody's basket right on this device and stale on their phone.
  const { setQuantity, remove, clear } = useCartActions();

  if (cart.isEmpty) {
    return (
      <div>
        <PageHeader title="Your basket" breadcrumbs={[{ label: 'Home', to: paths.home }, { label: 'Basket' }]} />
        <EmptyState
          icon={<ShoppingBag className="h-10 w-10" />}
          title="Your basket is empty"
          description="Once you add something it will show up here, priced against live stock."
          action={
            <Button asChild>
              <Link to={paths.products}>Browse the catalogue</Link>
            </Button>
          }
        />
      </div>
    );
  }

  if (cart.error) {
    return (
      <div>
        <PageHeader title="Your basket" />
        <ErrorState
          error={normalizeError(cart.error)}
          onRetry={() => void cart.refetch()}
          title="Could not price your basket"
        />
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title="Your basket"
        description={`${cart.itemCount} ${cart.itemCount === 1 ? 'item' : 'items'}`}
        breadcrumbs={[{ label: 'Home', to: paths.home }, { label: 'Basket' }]}
        actions={
          <Button variant="ghost" size="sm" onClick={clear}>
            <Trash2 aria-hidden />
            Empty basket
          </Button>
        }
      />

      <div className="grid gap-8 lg:grid-cols-[1fr_320px]">
        <div className="space-y-3">
          {cart.lines.map((line) => (
            <CartLineRow
              key={line.productId}
              line={line}
              isLoading={cart.isLoading}
              onQuantityChange={(quantity) => setQuantity(line.productId, quantity)}
              onRemove={() => remove(line.productId)}
            />
          ))}
        </div>

        <div className="lg:sticky lg:top-24 lg:self-start">
          <Card>
            <CardContent className="space-y-4 p-6">
              <h2 className="font-semibold">Summary</h2>

              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">
                  Subtotal ({cart.itemCount} {cart.itemCount === 1 ? 'item' : 'items'})
                </span>
                <span className="tabular">{formatMoney(cart.total, cart.currency)}</span>
              </div>

              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">Delivery</span>
                <span className="text-muted-foreground">Calculated at checkout</span>
              </div>

              <Separator />

              <div className="flex items-baseline justify-between">
                <span className="font-medium">Total</span>
                <span className="text-xl font-semibold tabular">
                  {formatMoney(cart.total, cart.currency)}
                </span>
              </div>

              {/* Blocking issues are surfaced here, not on submit — the customer should know
                  before they start filling in an address. */}
              {cart.blockingIssues.length > 0 ? (
                <div
                  role="alert"
                  className="space-y-1 rounded-md border border-destructive/30 bg-destructive/5 p-3"
                >
                  {cart.blockingIssues.map((issue) => (
                    <p key={issue} className="flex gap-2 text-xs text-destructive">
                      <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
                      {issue}
                    </p>
                  ))}
                </div>
              ) : null}

              <Button className="w-full" size="lg" disabled={!cart.canCheckout} asChild={cart.canCheckout}>
                {cart.canCheckout ? (
                  <Link to={paths.checkout}>Checkout</Link>
                ) : (
                  <span>Checkout</span>
                )}
              </Button>

              <Button variant="ghost" className="w-full" asChild>
                <Link to={paths.products}>Continue shopping</Link>
              </Button>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}

function CartLineRow({
  line,
  isLoading,
  onQuantityChange,
  onRemove,
}: {
  line: PricedCartLine;
  isLoading: boolean;
  onQuantityChange: (quantity: number) => void;
  onRemove: () => void;
}) {
  const { product } = line;
  const hasIssue = line.outOfStock || line.exceedsStock;

  return (
    <Card className={cn(hasIssue && 'border-destructive/40')}>
      <CardContent className="flex gap-4 p-4">
        <Link
          to={paths.product(line.productId)}
          className="h-20 w-20 shrink-0 overflow-hidden rounded-md border bg-muted"
        >
          {product?.imageUrl ? (
            <img
              src={product.imageUrl}
              alt={product.name}
              loading="lazy"
              className="h-full w-full object-cover"
            />
          ) : (
            <div className="flex h-full items-center justify-center text-muted-foreground">
              <ImageOff className="h-5 w-5" aria-hidden />
            </div>
          )}
        </Link>

        <div className="min-w-0 flex-1">
          <Link
            to={paths.product(line.productId)}
            className="line-clamp-2 text-sm font-medium hover:underline"
          >
            {product?.name ?? 'Loading…'}
          </Link>

          {isLoading && !product ? (
            <Skeleton className="mt-2 h-4 w-24" />
          ) : (
            <p className="mt-1 text-sm text-muted-foreground tabular">
              {formatMoney(line.unitPrice, line.currency)} each
            </p>
          )}

          {line.outOfStock ? (
            <Badge variant="destructive" className="mt-2">
              Out of stock
            </Badge>
          ) : line.exceedsStock && product ? (
            <Badge variant="warning" className="mt-2">
              Only {product.availableQuantity} available
            </Badge>
          ) : null}

          <div className="mt-3 flex items-center gap-3">
            <QuantityStepper
              value={line.quantity}
              onChange={onQuantityChange}
              max={Math.max(1, product?.availableQuantity ?? 999)}
              size="sm"
              disabled={line.outOfStock}
            />
            <Button
              variant="ghost"
              size="sm"
              onClick={onRemove}
              aria-label={`Remove ${product?.name ?? 'item'} from basket`}
            >
              <Trash2 aria-hidden />
            </Button>
          </div>
        </div>

        <p className="shrink-0 text-sm font-semibold tabular">
          {formatMoney(line.subtotal, line.currency)}
        </p>
      </CardContent>
    </Card>
  );
}
