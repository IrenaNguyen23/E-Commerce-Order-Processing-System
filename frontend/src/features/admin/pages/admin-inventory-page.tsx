import { AlertTriangle, Boxes, Info } from 'lucide-react';
import { useCallback, useState } from 'react';

import { DataTable, type Column } from '@/components/common/data-table';
import { PageHeader } from '@/components/common/page-header';
import { PagePagination } from '@/components/common/pagination';
import { SearchInput } from '@/components/common/search-input';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { useProducts, useUpdateStock } from '@/features/product/hooks';
import { stockLevel, type Product, type StockOperation } from '@/features/product/types';
import { useQueryParams } from '@/hooks/use-query-params';
import { normalizeError } from '@/services/error';
import { ADMIN_PAGE_SIZE } from '@/shared/config';
import { formatDateTime } from '@/utils/format';

const DEFAULTS = {
  page: 0,
  size: ADMIN_PAGE_SIZE,
  search: '',
  sortBy: 'sku',
  direction: 'asc' as 'asc' | 'desc',
};

export default function AdminInventoryPage() {
  const { params, setParams } = useQueryParams(DEFAULTS);
  const [adjusting, setAdjusting] = useState<Product | null>(null);

  const query = useProducts({
    page: params.page,
    size: params.size,
    search: params.search || undefined,
    activeOnly: false,
    sortBy: params.sortBy,
    direction: params.direction,
  });

  const handleSearch = useCallback(
    (search: string) => setParams({ search, page: 0 }),
    [setParams],
  );

  const columns: Column<Product>[] = [
    {
      id: 'sku',
      header: 'SKU',
      cell: (product) => (
        <div className="min-w-0">
          <p className="font-mono text-sm">{product.sku}</p>
          <p className="truncate text-xs text-muted-foreground">{product.name}</p>
        </div>
      ),
    },
    {
      id: 'available',
      header: 'Available',
      className: 'text-right',
      cell: (product) => <span className="tabular font-medium">{product.availableQuantity}</span>,
    },
    {
      id: 'reserved',
      header: 'Reserved',
      className: 'text-right',
      cell: (product) => (
        <span className="tabular text-muted-foreground">{product.reservedQuantity}</span>
      ),
    },
    {
      id: 'level',
      header: 'Level',
      cell: (product) => {
        const level = stockLevel(product);
        return level === 'out' ? (
          <Badge variant="destructive">Out of stock</Badge>
        ) : level === 'low' ? (
          <Badge variant="warning">Low</Badge>
        ) : (
          <Badge variant="success">Healthy</Badge>
        );
      },
    },
    {
      id: 'updated',
      header: 'Updated',
      hideOnMobile: true,
      cell: (product) => (
        <span className="text-sm text-muted-foreground">{formatDateTime(product.updatedAt)}</span>
      ),
    },
    {
      id: 'actions',
      header: '',
      className: 'text-right',
      cell: (product) => (
        <Button variant="outline" size="sm" onClick={() => setAdjusting(product)}>
          Adjust
        </Button>
      ),
    },
  ];

  return (
    <div>
      <PageHeader
        title="Inventory"
        description="Stock levels across the catalogue."
        breadcrumbs={[{ label: 'Admin' }, { label: 'Inventory' }]}
      />

      <div className="space-y-4">
        {/* The distinction that trips people up, said once, up front. */}
        <div className="flex gap-3 rounded-lg border bg-muted/40 p-4">
          <Info className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" aria-hidden />
          <p className="text-sm text-muted-foreground">
            <span className="font-medium text-foreground">Available</span> is what a new order can
            consume. <span className="font-medium text-foreground">Reserved</span> is held by
            orders whose saga is still running — adjusting stock never touches it, because those
            units are already promised.
          </p>
        </div>

        <SearchInput
          value={params.search}
          onChange={handleSearch}
          placeholder="Search by name or SKU…"
          className="max-w-sm"
        />

        <DataTable
          columns={columns}
          rows={query.data?.content}
          getRowId={(product) => product.id}
          isLoading={query.isLoading}
          error={query.error ? normalizeError(query.error) : null}
          onRetry={() => void query.refetch()}
          skeletonRows={8}
          emptyTitle="Nothing to show"
          emptyDescription="Add products before you can manage their stock."
        />

        <PagePagination data={query.data} onPageChange={(page) => setParams({ page })} />
      </div>

      <AdjustStockDialog product={adjusting} onClose={() => setAdjusting(null)} />
    </div>
  );
}

function AdjustStockDialog({
  product,
  onClose,
}: {
  product: Product | null;
  onClose: () => void;
}) {
  const updateStock = useUpdateStock();
  const [operation, setOperation] = useState<StockOperation>('SET');
  const [quantity, setQuantity] = useState('0');
  const [reason, setReason] = useState('');

  const parsed = Number(quantity);
  const isValid = Number.isFinite(parsed) && parsed >= 0;

  // Show the operator what the number will become before they commit to it.
  const projected = product
    ? operation === 'SET'
      ? parsed
      : operation === 'INCREASE'
        ? product.availableQuantity + parsed
        : Math.max(0, product.availableQuantity - parsed)
    : 0;

  const handleSubmit = async () => {
    if (!product || !isValid) return;
    await updateStock.mutateAsync({
      id: product.id,
      payload: { quantity: parsed, operation, reason: reason || undefined },
    });
    setQuantity('0');
    setReason('');
    setOperation('SET');
    onClose();
  };

  return (
    <Dialog
      open={product !== null}
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Adjust stock</DialogTitle>
        </DialogHeader>

        {product ? (
          <div className="space-y-4">
            <div className="rounded-md border bg-muted/40 p-3">
              <p className="font-mono text-sm">{product.sku}</p>
              <p className="text-sm text-muted-foreground">{product.name}</p>
              <p className="mt-2 text-sm tabular">
                Available <span className="font-medium">{product.availableQuantity}</span>
                {product.reservedQuantity > 0 ? (
                  <span className="text-muted-foreground">
                    {' '}
                    · {product.reservedQuantity} reserved
                  </span>
                ) : null}
              </p>
            </div>

            <div>
              <Label htmlFor="operation">Operation</Label>
              <Select
                value={operation}
                onValueChange={(value) => setOperation(value as StockOperation)}
              >
                <SelectTrigger id="operation" className="mt-2">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="SET">Set to</SelectItem>
                  <SelectItem value="INCREASE">Increase by</SelectItem>
                  <SelectItem value="DECREASE">Decrease by</SelectItem>
                </SelectContent>
              </Select>
            </div>

            <div>
              <Label htmlFor="quantity">Quantity</Label>
              <Input
                id="quantity"
                type="number"
                min="0"
                className="mt-2 tabular"
                value={quantity}
                onChange={(event) => setQuantity(event.target.value)}
              />
            </div>

            <div>
              <Label htmlFor="reason">
                Reason <span className="text-muted-foreground">(optional)</span>
              </Label>
              <Input
                id="reason"
                className="mt-2"
                placeholder="Stock count, delivery, breakage…"
                value={reason}
                onChange={(event) => setReason(event.target.value)}
              />
            </div>

            {isValid ? (
              <p className="rounded-md bg-muted/60 p-3 text-sm">
                Available will become <span className="font-semibold tabular">{projected}</span>.
                {operation === 'DECREASE' && parsed > product.availableQuantity ? (
                  <span className="mt-1 flex items-center gap-1.5 text-warning">
                    <AlertTriangle className="h-3.5 w-3.5" aria-hidden />
                    Clamped at zero — stock never goes negative.
                  </span>
                ) : null}
              </p>
            ) : null}
          </div>
        ) : null}

        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} loading={updateStock.isPending} disabled={!isValid}>
            <Boxes aria-hidden />
            Apply
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
