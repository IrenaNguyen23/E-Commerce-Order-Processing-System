import { Link } from 'react-router-dom';

import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { paths } from '@/routes/paths';
import { formatMoney } from '@/utils/format';

import type { Order } from '../types';

/**
 * The lines of a placed order.
 *
 * Names and prices come from the order, never from the catalogue: they were snapshotted at
 * checkout, and a later price change must not rewrite what the customer agreed to pay. The
 * product link is a convenience — it may well point at a product that now costs something else.
 */
export function OrderItemsTable({ order }: { order: Order }) {
  return (
    <div className="rounded-lg border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Item</TableHead>
            <TableHead className="hidden sm:table-cell">SKU</TableHead>
            <TableHead className="text-right">Qty</TableHead>
            <TableHead className="hidden text-right sm:table-cell">Unit price</TableHead>
            <TableHead className="text-right">Subtotal</TableHead>
          </TableRow>
        </TableHeader>

        <TableBody>
          {order.items.map((item) => (
            <TableRow key={`${item.productId}-${item.sku}`}>
              <TableCell className="font-medium">
                <div className="flex items-center gap-3">
                  {/* The snapshot, not the catalogue. A product that has since been re-shot or
                      replaced must not change what this order looks like. */}
                  {item.productImageUrl ? (
                    <img
                      src={item.productImageUrl}
                      alt=""
                      loading="lazy"
                      className="h-10 w-10 shrink-0 rounded border object-cover"
                    />
                  ) : null}
                  <div className="min-w-0">
                    <Link
                      to={paths.product(item.productId)}
                      className="transition-colors hover:text-primary hover:underline"
                    >
                      {item.productName}
                    </Link>
                    {item.productCategory ? (
                      <p className="text-xs text-muted-foreground">{item.productCategory}</p>
                    ) : null}
                  </div>
                </div>
              </TableCell>
              <TableCell className="hidden font-mono text-xs text-muted-foreground sm:table-cell">
                {item.sku}
              </TableCell>
              <TableCell className="text-right tabular">{item.quantity}</TableCell>
              <TableCell className="hidden text-right tabular sm:table-cell">
                {/* Only shown when the two differ, which today they never do. When a promotion
                    engine exists, this is where a customer sees what they saved rather than
                    just a number they cannot account for. */}
                {item.discountAmount > 0 ? (
                  <>
                    <span className="mr-2 text-muted-foreground line-through">
                      {formatMoney(item.listPrice, order.currency)}
                    </span>
                    {formatMoney(item.unitPrice, order.currency)}
                  </>
                ) : (
                  formatMoney(item.unitPrice, order.currency)
                )}
              </TableCell>
              <TableCell className="text-right font-medium tabular">
                {formatMoney(item.subtotal, order.currency)}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>

      {order.discountTotal > 0 ? (
        <div className="border-t px-4 py-3 text-sm">
          <div className="flex items-center justify-between text-muted-foreground">
            <span>Subtotal</span>
            <span className="tabular">{formatMoney(order.subtotalAmount, order.currency)}</span>
          </div>
          <div className="mt-1 flex items-center justify-between text-success">
            <span>Discount</span>
            <span className="tabular">
              &minus;{formatMoney(order.discountTotal, order.currency)}
            </span>
          </div>
        </div>
      ) : null}

      <div className="flex items-center justify-between border-t px-4 py-3">
        <span className="text-sm font-medium">
          Total
          <span className="ml-2 font-normal text-muted-foreground tabular">
            ({order.itemCount} {order.itemCount === 1 ? 'item' : 'items'})
          </span>
        </span>
        <span className="text-lg font-semibold tabular">
          {formatMoney(order.totalAmount, order.currency)}
        </span>
      </div>
    </div>
  );
}
