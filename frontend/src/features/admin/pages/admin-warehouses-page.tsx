import { AlertTriangle, Pencil, Plus, Warehouse as WarehouseIcon } from 'lucide-react';
import { useState } from 'react';

import { PageHeader } from '@/components/common/page-header';
import { DataTable, type Column } from '@/components/common/data-table';
import { EmptyState, ErrorState } from '@/components/common/states';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';

import {
  useCreateWarehouse,
  useLowStock,
  useSetWarehouseStock,
  useUpdateWarehouse,
  useWarehouseContents,
  useWarehouses,
  type StockLevel,
  type Warehouse,
} from '../warehouses-api';

/**
 * Where stock physically is.
 *
 * <h2>The screen exists to answer one question</h2>
 *
 * "We have twelve — where are they?" A product list can only say twelve, and twelve split six and
 * six across two countries behaves nothing like twelve in one building: an order for eight either
 * ships in two parcels or cannot ship at all.
 *
 * <h2>Reserved units are shown and cannot be edited</h2>
 *
 * They belong to orders whose saga is still running. Editing them would break the compensation
 * arithmetic — a later release would put back stock that had already been counted away — so the
 * column is read-only and says why.
 *
 * <h2>Closing a building is not deleting it</h2>
 *
 * There is no delete, on purpose. A warehouse appears on reservations, on shipments and in the
 * history of every order it filled. Turning it inactive stops **new** allocations and leaves
 * everything already promised alone, which is what somebody closing a building wants during the
 * weeks it actually takes.
 */
export default function AdminWarehousesPage() {
  const warehouses = useWarehouses(true);
  const lowStock = useLowStock();
  const createWarehouse = useCreateWarehouse();
  const updateWarehouse = useUpdateWarehouse();

  const [selected, setSelected] = useState<Warehouse | null>(null);
  const [editing, setEditing] = useState<Warehouse | null>(null);
  const [creating, setCreating] = useState(false);

  const columns: Column<Warehouse>[] = [
    {
      id: 'code',
      header: 'Warehouse',
      cell: (row) => (
        <button
          type="button"
          className="text-left"
          onClick={() => setSelected(row)}
        >
          <span className="font-mono text-xs text-muted-foreground">{row.code}</span>
          <span className="block font-medium underline-offset-4 hover:underline">{row.name}</span>
        </button>
      ),
    },
    {
      id: 'where',
      header: 'Where',
      cell: (row) => (
        <span className="text-sm">
          {row.city ? `${row.city}, ` : ''}
          {row.countryCode}
        </span>
      ),
    },
    {
      id: 'priority',
      header: 'Priority',
      className: 'text-right',
      cell: (row) => (
        <span className="tabular text-muted-foreground" title="Lower goes first">
          {row.priority}
        </span>
      ),
    },
    {
      id: 'active',
      header: 'Taking orders',
      cell: (row) =>
        row.active ? (
          <Badge variant="secondary">Yes</Badge>
        ) : (
          <Badge variant="outline" title="Existing reservations are unaffected">
            Closed to new orders
          </Badge>
        ),
    },
    {
      id: 'actions',
      header: '',
      className: 'text-right',
      cell: (row) => (
        <Button
          variant="ghost"
          size="icon"
          onClick={() => setEditing(row)}
          aria-label={`Edit ${row.name}`}
        >
          <Pencil aria-hidden />
        </Button>
      ),
    },
  ];

  return (
    <div>
      <PageHeader
        title="Warehouses"
        description="Where stock is, and how much of it is in each building."
        breadcrumbs={[{ label: 'Admin', to: paths.admin.dashboard }, { label: 'Warehouses' }]}
        actions={
          <Button onClick={() => setCreating(true)}>
            <Plus aria-hidden />
            Open a warehouse
          </Button>
        }
      />

      <DataTable
        columns={columns}
        rows={warehouses.data}
        getRowId={(row) => row.id}
        isLoading={warehouses.isLoading}
        error={warehouses.error ? normalizeError(warehouses.error) : null}
        onRetry={() => void warehouses.refetch()}
        skeletonRows={3}
        emptyTitle="No warehouses"
        emptyDescription="Stock has to live somewhere before it can be allocated."
        emptyAction={
          <Button onClick={() => setCreating(true)}>
            <Plus aria-hidden />
            Open a warehouse
          </Button>
        }
      />

      {/* Per building, because a shop can be short in Milan and comfortable in Amsterdam — which
          is exactly what a single global figure hides. */}
      {(lowStock.data?.length ?? 0) > 0 ? (
        <Card className="mt-8 border-amber-500/40">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <AlertTriangle className="h-4 w-4 text-amber-600" aria-hidden />
              At or below the reorder point
            </CardTitle>
          </CardHeader>
          <CardContent>
            <ul className="space-y-1 text-sm">
              {lowStock.data?.map((level) => (
                <li
                  key={`${level.warehouseId}-${level.productId}`}
                  className="flex justify-between gap-3"
                >
                  <span className="font-mono text-xs">{level.sku ?? level.productId}</span>
                  <span className="tabular text-muted-foreground">
                    {level.availableQuantity} left in{' '}
                    {warehouses.data?.find((house) => house.id === level.warehouseId)?.code ??
                      'a warehouse'}
                  </span>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      ) : null}

      <WarehouseContents
        warehouse={selected}
        onClose={() => setSelected(null)}
      />

      <WarehouseDialog
        open={creating || editing !== null}
        warehouse={editing}
        pending={createWarehouse.isPending || updateWarehouse.isPending}
        onClose={() => {
          setCreating(false);
          setEditing(null);
        }}
        onSubmit={(payload) => {
          if (editing) {
            updateWarehouse.mutate({ id: editing.id, payload });
          } else {
            createWarehouse.mutate(payload);
          }
          setCreating(false);
          setEditing(null);
        }}
      />
    </div>
  );
}

/** What is in one building, with an editable available count. */
function WarehouseContents({
  warehouse,
  onClose,
}: {
  warehouse: Warehouse | null;
  onClose: () => void;
}) {
  const contents = useWarehouseContents(warehouse?.id);
  const setStock = useSetWarehouseStock();
  const [editingLevel, setEditingLevel] = useState<StockLevel | null>(null);
  const [quantity, setQuantity] = useState('0');

  const columns: Column<StockLevel>[] = [
    {
      id: 'sku',
      header: 'SKU',
      cell: (row) => <span className="font-mono text-xs">{row.sku ?? row.productId}</span>,
    },
    {
      id: 'available',
      header: 'Available',
      className: 'text-right',
      cell: (row) => (
        <span className={row.belowReorderLevel ? 'tabular text-amber-700' : 'tabular'}>
          {row.availableQuantity}
        </span>
      ),
    },
    {
      id: 'reserved',
      header: 'Held',
      className: 'text-right',
      cell: (row) => (
        // Read-only, and the title says why. Editing units a running saga is holding would break
        // the compensation arithmetic.
        <span
          className="tabular text-muted-foreground"
          title="Held by orders still being processed. Not editable — a release would then put back stock already counted away."
        >
          {row.reservedQuantity}
        </span>
      ),
    },
    {
      id: 'reorder',
      header: 'Reorder at',
      className: 'text-right',
      cell: (row) => <span className="tabular text-muted-foreground">{row.reorderLevel}</span>,
    },
    {
      id: 'actions',
      header: '',
      className: 'text-right',
      cell: (row) => (
        <Button
          variant="ghost"
          size="sm"
          onClick={() => {
            setEditingLevel(row);
            setQuantity(String(row.availableQuantity));
          }}
        >
          Set
        </Button>
      ),
    },
  ];

  return (
    <>
      <Dialog open={warehouse !== null} onOpenChange={(open) => !open && onClose()}>
        <DialogContent className="max-w-3xl">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <WarehouseIcon className="h-4 w-4 text-muted-foreground" aria-hidden />
              {warehouse?.name}
              <span className="font-mono text-xs text-muted-foreground">{warehouse?.code}</span>
            </DialogTitle>
          </DialogHeader>

          {contents.isError ? (
            <ErrorState
              error={normalizeError(contents.error)}
              onRetry={() => void contents.refetch()}
            />
          ) : (contents.data?.length ?? 0) === 0 && !contents.isLoading ? (
            <EmptyState
              icon={<WarehouseIcon className="h-10 w-10" />}
              title="Nothing here yet"
              description="Set a quantity for a product from its own page, or from the product list."
            />
          ) : (
            <div className="max-h-[60vh] overflow-y-auto">
              <DataTable
                columns={columns}
                rows={contents.data}
                getRowId={(row) => row.productId}
                isLoading={contents.isLoading}
                skeletonRows={4}
                emptyTitle="Nothing here"
                emptyDescription=""
              />
            </div>
          )}
        </DialogContent>
      </Dialog>

      <Dialog
        open={editingLevel !== null}
        onOpenChange={(open) => !open && setEditingLevel(null)}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              Set stock for {editingLevel?.sku ?? 'this product'}
            </DialogTitle>
          </DialogHeader>

          <div className="space-y-2">
            <Label htmlFor="stock-quantity">Units available in {warehouse?.code}</Label>
            <Input
              id="stock-quantity"
              type="number"
              min={0}
              value={quantity}
              onChange={(event) => setQuantity(event.target.value)}
            />
            <p className="text-xs text-muted-foreground">
              {editingLevel?.reservedQuantity
                ? `${editingLevel.reservedQuantity} unit(s) here are held by orders still being processed. Those are left alone.`
                : 'Nothing here is currently held by an order.'}
            </p>
          </div>

          <DialogFooter className="gap-2">
            <Button variant="ghost" onClick={() => setEditingLevel(null)}>
              Cancel
            </Button>
            <Button
              loading={setStock.isPending}
              onClick={() => {
                if (editingLevel && warehouse) {
                  setStock.mutate({
                    warehouseId: warehouse.id,
                    productId: editingLevel.productId,
                    quantity: Math.max(0, Number(quantity) || 0),
                  });
                }
                setEditingLevel(null);
              }}
            >
              Set stock
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

function WarehouseDialog({
  open,
  warehouse,
  pending,
  onClose,
  onSubmit,
}: {
  open: boolean;
  warehouse: Warehouse | null;
  pending: boolean;
  onClose: () => void;
  onSubmit: (payload: {
    code: string;
    name: string;
    countryCode: string;
    city?: string;
    priority?: number;
    active?: boolean;
  }) => void;
}) {
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [countryCode, setCountryCode] = useState('');
  const [city, setCity] = useState('');
  const [priority, setPriority] = useState('100');
  const [active, setActive] = useState(true);

  const [loadedFor, setLoadedFor] = useState<string | null>(null);
  const key = warehouse?.id ?? '__new__';
  if (open && loadedFor !== key) {
    setLoadedFor(key);
    setCode(warehouse?.code ?? '');
    setName(warehouse?.name ?? '');
    setCountryCode(warehouse?.countryCode ?? '');
    setCity(warehouse?.city ?? '');
    setPriority(String(warehouse?.priority ?? 100));
    setActive(warehouse?.active ?? true);
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) {
          setLoadedFor(null);
          onClose();
        }
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{warehouse ? 'Edit warehouse' : 'Open a warehouse'}</DialogTitle>
        </DialogHeader>

        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <Label htmlFor="warehouse-code">Code</Label>
            <Input
              id="warehouse-code"
              className="mt-2 font-mono uppercase"
              maxLength={20}
              placeholder="MIL"
              value={code}
              disabled={warehouse !== null}
              onChange={(event) => setCode(event.target.value.toUpperCase())}
            />
            <p className="mt-1 text-xs text-muted-foreground">
              {warehouse
                ? 'Cannot be changed: it is on paperwork and in every log line about this building.'
                : 'Short and stable. It cannot be changed afterwards.'}
            </p>
          </div>

          <div>
            <Label htmlFor="warehouse-country">Country</Label>
            <Input
              id="warehouse-country"
              className="mt-2 uppercase"
              maxLength={2}
              placeholder="IT"
              value={countryCode}
              onChange={(event) => setCountryCode(event.target.value.toUpperCase())}
            />
            <p className="mt-1 text-xs text-muted-foreground">
              Checked before priority: shipping from inside the destination country saves a customs
              form and usually a day.
            </p>
          </div>

          <div className="sm:col-span-2">
            <Label htmlFor="warehouse-name">Name</Label>
            <Input
              id="warehouse-name"
              className="mt-2"
              maxLength={150}
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
          </div>

          <div>
            <Label htmlFor="warehouse-city">City (optional)</Label>
            <Input
              id="warehouse-city"
              className="mt-2"
              maxLength={100}
              value={city}
              onChange={(event) => setCity(event.target.value)}
            />
          </div>

          <div>
            <Label htmlFor="warehouse-priority">Priority</Label>
            <Input
              id="warehouse-priority"
              type="number"
              className="mt-2"
              value={priority}
              onChange={(event) => setPriority(event.target.value)}
            />
            <p className="mt-1 text-xs text-muted-foreground">Lower goes first.</p>
          </div>

          <label className="flex items-center gap-2 text-sm sm:col-span-2">
            <Checkbox
              checked={active}
              onCheckedChange={(checked) => setActive(checked === true)}
            />
            <span>
              Taking new orders
              <span className="block text-xs text-muted-foreground">
                Turning this off stops new allocations. Orders already holding stock here are
                unaffected.
              </span>
            </span>
          </label>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button
            loading={pending}
            disabled={!code.trim() || !name.trim() || countryCode.length !== 2 || pending}
            onClick={() =>
              onSubmit({
                code: code.trim(),
                name: name.trim(),
                countryCode: countryCode.trim(),
                city: city.trim() || undefined,
                priority: Number(priority) || 100,
                active,
              })
            }
          >
            {warehouse ? 'Save changes' : 'Open warehouse'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
