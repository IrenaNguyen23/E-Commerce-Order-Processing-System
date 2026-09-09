import { Pencil, Plus, Ticket, Trash2, Users } from 'lucide-react';
import { useState } from 'react';

import { ConfirmDialog } from '@/components/common/confirm-dialog';
import { DataTable, type Column } from '@/components/common/data-table';
import { PageHeader } from '@/components/common/page-header';
import { PagePagination } from '@/components/common/pagination';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { formatDate, formatDateTime, formatMoney } from '@/utils/format';

import {
  couponStatus,
  DISCOUNT_TYPE_LABELS,
  useCoupons,
  useCouponRedemptions,
  useCreateCoupon,
  useDeleteCoupon,
  useUpdateCoupon,
  type Coupon,
  type CouponPayload,
  type DiscountType,
} from '../coupons-api';

/**
 * Discount codes.
 *
 * <h2>Two different questions about whether a code works</h2>
 *
 * `active` is the operator's switch. `live` is whether it would actually be accepted right now,
 * which also depends on the window and on how much allowance is left. Showing only the first
 * leaves somebody staring at a code that is plainly switched on and does nothing.
 *
 * <h2>The percentage field takes a percentage</h2>
 *
 * The API takes a fraction — `0.10` is ten per cent — and refuses `10` rather than guessing. That
 * is right for an API and wrong for a form: an operator types 10 into a field labelled `%`, and
 * the conversion happens here, once, where it is visible.
 *
 * <h2>Deleting is rarely what somebody wants</h2>
 *
 * A code that has been used cannot be deleted — the redemption rows are the record of what the
 * campaign cost. The button is disabled with the reason on it, and the thing an operator almost
 * always meant is the switch.
 */
export default function AdminCouponsPage() {
  const [page, setPage] = useState(0);
  const [onlyActive, setOnlyActive] = useState<boolean | undefined>(undefined);

  const query = useCoupons(onlyActive, page);
  const createCoupon = useCreateCoupon();
  const updateCoupon = useUpdateCoupon();
  const deleteCoupon = useDeleteCoupon();

  const [editing, setEditing] = useState<Coupon | null>(null);
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<Coupon | null>(null);
  const [inspecting, setInspecting] = useState<Coupon | null>(null);

  const columns: Column<Coupon>[] = [
    {
      id: 'code',
      header: 'Code',
      cell: (row) => (
        <div className="min-w-0">
          <span className="font-mono text-sm font-medium">{row.code}</span>
          {row.description ? (
            <span className="block text-xs text-muted-foreground">{row.description}</span>
          ) : null}
        </div>
      ),
    },
    {
      id: 'worth',
      header: 'Worth',
      cell: (row) => <span className="text-sm">{describeValue(row)}</span>,
    },
    {
      id: 'used',
      header: 'Used',
      className: 'text-right',
      cell: (row) => (
        <button
          type="button"
          className="tabular underline-offset-4 hover:underline"
          onClick={() => setInspecting(row)}
          title="See who used it"
        >
          {row.redemptionCount}
          {row.maxRedemptions !== null ? ` / ${row.maxRedemptions}` : ''}
        </button>
      ),
    },
    {
      id: 'window',
      header: 'Runs',
      hideOnMobile: true,
      cell: (row) => (
        <span className="text-xs text-muted-foreground">
          {row.validFrom || row.validUntil
            ? `${row.validFrom ? formatDate(row.validFrom) : 'now'} — ${
                row.validUntil ? formatDate(row.validUntil) : 'no end'
              }`
            : 'No end date'}
        </span>
      ),
    },
    {
      id: 'status',
      header: 'Status',
      cell: (row) => {
        const status = couponStatus(row);
        return (
          <Badge
            variant={
              status.tone === 'live'
                ? 'secondary'
                : status.tone === 'spent'
                  ? 'outline'
                  : 'outline'
            }
          >
            {status.label}
          </Badge>
        );
      },
    },
    {
      id: 'actions',
      header: '',
      className: 'text-right',
      cell: (row) => (
        <div className="flex justify-end gap-1">
          <Button
            variant="ghost"
            size="icon"
            onClick={() => setEditing(row)}
            aria-label={`Edit ${row.code}`}
          >
            <Pencil aria-hidden />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            // Disabled with the reason on it, rather than hidden. An operator who cannot find the
            // button assumes it is missing, not that it does not apply here.
            disabled={row.redemptionCount > 0}
            title={
              row.redemptionCount > 0
                ? `Used ${row.redemptionCount} time(s). Switch it off instead — that stops it now and keeps the record of what it cost.`
                : undefined
            }
            onClick={() => setDeleting(row)}
            aria-label={`Delete ${row.code}`}
          >
            <Trash2 aria-hidden />
          </Button>
        </div>
      ),
    },
  ];

  return (
    <div>
      <PageHeader
        title="Coupons"
        description="Discount codes, and what they have cost."
        breadcrumbs={[{ label: 'Admin', to: paths.admin.dashboard }, { label: 'Coupons' }]}
        actions={
          <Button onClick={() => setCreating(true)}>
            <Plus aria-hidden />
            New code
          </Button>
        }
      />

      <div className="mb-4 flex gap-2">
        {([undefined, true, false] as const).map((filter) => (
          <Button
            key={String(filter)}
            variant={onlyActive === filter ? 'default' : 'outline'}
            size="sm"
            onClick={() => {
              setOnlyActive(filter);
              setPage(0);
            }}
          >
            {filter === undefined ? 'All' : filter ? 'Switched on' : 'Switched off'}
          </Button>
        ))}
      </div>

      <DataTable
        columns={columns}
        rows={query.data?.content}
        getRowId={(row) => row.id}
        isLoading={query.isLoading}
        error={query.error ? normalizeError(query.error) : null}
        onRetry={() => void query.refetch()}
        skeletonRows={5}
        emptyTitle="No codes yet"
        emptyDescription="A code is worth a fraction off the goods, a fixed amount, or free delivery."
        emptyAction={
          <Button onClick={() => setCreating(true)}>
            <Plus aria-hidden />
            New code
          </Button>
        }
      />

      <PagePagination data={query.data} onPageChange={setPage} />

      <CouponDialog
        open={creating || editing !== null}
        coupon={editing}
        pending={createCoupon.isPending || updateCoupon.isPending}
        onClose={() => {
          setCreating(false);
          setEditing(null);
        }}
        onSubmit={(payload) => {
          if (editing) {
            updateCoupon.mutate({ id: editing.id, payload });
          } else {
            createCoupon.mutate(payload);
          }
          setCreating(false);
          setEditing(null);
        }}
      />

      <RedemptionsDialog coupon={inspecting} onClose={() => setInspecting(null)} />

      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={`Delete ${deleting?.code ?? 'this code'}?`}
        description="Nobody has used it, so there is nothing to lose. A used code cannot be deleted — switch it off instead."
        confirmLabel="Delete"
        destructive
        onConfirm={() => {
          if (deleting) {
            deleteCoupon.mutate(deleting.id);
          }
          setDeleting(null);
        }}
      />
    </div>
  );
}

function describeValue(coupon: Coupon): string {
  switch (coupon.type) {
    case 'PERCENTAGE':
      // Back into a percentage for display. Nobody reads 0.1 as ten per cent at a glance.
      return `${(coupon.value * 100).toFixed(coupon.value * 100 % 1 === 0 ? 0 : 1)}% off`;
    case 'FIXED_AMOUNT':
      return `${formatMoney(coupon.value, coupon.currency ?? 'EUR')} off`;
    case 'FREE_SHIPPING':
      return 'Free delivery';
  }
}

/** Who used a code, and what it actually cost. */
function RedemptionsDialog({
  coupon,
  onClose,
}: {
  coupon: Coupon | null;
  onClose: () => void;
}) {
  const redemptions = useCouponRedemptions(coupon?.id);

  const total = (redemptions.data ?? []).reduce(
    (sum, redemption) => sum + redemption.discountAmount,
    0,
  );

  return (
    <Dialog open={coupon !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Users className="h-4 w-4 text-muted-foreground" aria-hidden />
            <span className="font-mono">{coupon?.code}</span>
          </DialogTitle>
        </DialogHeader>

        {redemptions.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : (redemptions.data?.length ?? 0) === 0 ? (
          <p className="text-sm text-muted-foreground">Nobody has used this yet.</p>
        ) : (
          <>
            {/* The sum of what it was actually worth per order, not the face value times uses:
                a code can be worth less than its value when the basket was smaller than the
                discount, and that difference is the difference between a budget and a guess. */}
            <p className="text-sm">
              <span className="font-medium">{redemptions.data?.length}</span> use(s), costing{' '}
              <span className="font-medium tabular">
                {formatMoney(total, redemptions.data?.[0]?.currency ?? 'EUR')}
              </span>
            </p>

            <ul className="max-h-[50vh] space-y-2 overflow-y-auto">
              {redemptions.data?.map((redemption) => (
                <li
                  key={redemption.orderId}
                  className="flex items-center justify-between gap-3 rounded-md border p-3 text-sm"
                >
                  <span className="min-w-0">
                    <span className="font-mono text-xs">{redemption.orderId.slice(0, 8)}</span>
                    <span className="block text-xs text-muted-foreground">
                      {formatDateTime(redemption.redeemedAt)}
                    </span>
                  </span>
                  <span className="shrink-0 tabular">
                    −{formatMoney(redemption.discountAmount, redemption.currency)}
                  </span>
                </li>
              ))}
            </ul>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}

function CouponDialog({
  open,
  coupon,
  pending,
  onClose,
  onSubmit,
}: {
  open: boolean;
  coupon: Coupon | null;
  pending: boolean;
  onClose: () => void;
  onSubmit: (payload: CouponPayload) => void;
}) {
  const [code, setCode] = useState('');
  const [description, setDescription] = useState('');
  const [type, setType] = useState<DiscountType>('PERCENTAGE');
  const [percent, setPercent] = useState('10');
  const [amount, setAmount] = useState('10');
  const [currency, setCurrency] = useState('EUR');
  const [minimumBasket, setMinimumBasket] = useState('');
  const [maxRedemptions, setMaxRedemptions] = useState('');
  const [perCustomerLimit, setPerCustomerLimit] = useState('');
  const [validUntil, setValidUntil] = useState('');
  const [active, setActive] = useState(true);

  const [loadedFor, setLoadedFor] = useState<string | null>(null);
  const key = coupon?.id ?? '__new__';
  if (open && loadedFor !== key) {
    setLoadedFor(key);
    setCode(coupon?.code ?? '');
    setDescription(coupon?.description ?? '');
    setType(coupon?.type ?? 'PERCENTAGE');
    setPercent(coupon?.type === 'PERCENTAGE' ? String(coupon.value * 100) : '10');
    setAmount(coupon?.type === 'FIXED_AMOUNT' ? String(coupon.value) : '10');
    setCurrency(coupon?.currency ?? 'EUR');
    setMinimumBasket(coupon?.minimumBasket != null ? String(coupon.minimumBasket) : '');
    setMaxRedemptions(coupon?.maxRedemptions != null ? String(coupon.maxRedemptions) : '');
    setPerCustomerLimit(coupon?.perCustomerLimit != null ? String(coupon.perCustomerLimit) : '');
    setValidUntil(coupon?.validUntil ? coupon.validUntil.slice(0, 10) : '');
    setActive(coupon?.active ?? true);
  }

  const value =
    type === 'PERCENTAGE'
      ? // The API takes a fraction and refuses a bare 10, on purpose. The form takes a
        // percentage because that is what an operator thinks in, and converts here.
        (Number(percent) || 0) / 100
      : type === 'FIXED_AMOUNT'
        ? Number(amount) || 0
        : 0;

  const percentOutOfRange = type === 'PERCENTAGE' && (Number(percent) <= 0 || Number(percent) >= 100);

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
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Ticket className="h-4 w-4 text-muted-foreground" aria-hidden />
            {coupon ? `Edit ${coupon.code}` : 'New discount code'}
          </DialogTitle>
        </DialogHeader>

        <div className="grid gap-4 sm:grid-cols-2">
          <div className="sm:col-span-2">
            <Label htmlFor="coupon-code">Code</Label>
            <Input
              id="coupon-code"
              className="mt-2 font-mono uppercase"
              maxLength={40}
              placeholder="WELCOME10"
              value={code}
              disabled={coupon !== null}
              onChange={(event) => setCode(event.target.value.toUpperCase())}
            />
            <p className="mt-1 text-xs text-muted-foreground">
              {coupon
                ? 'Cannot be changed: it is printed on posters and recorded on every order that used it.'
                : 'What the customer types. Matched case-insensitively, and cannot be changed afterwards.'}
            </p>
          </div>

          <div className="sm:col-span-2">
            <Label htmlFor="coupon-description">Description (optional)</Label>
            <Input
              id="coupon-description"
              className="mt-2"
              maxLength={200}
              placeholder="10% off your first order"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
            />
          </div>

          <div>
            <Label htmlFor="coupon-type">Kind</Label>
            <Select value={type} onValueChange={(next) => setType(next as DiscountType)}>
              <SelectTrigger id="coupon-type" className="mt-2">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {(Object.keys(DISCOUNT_TYPE_LABELS) as DiscountType[]).map((option) => (
                  <SelectItem key={option} value={option}>
                    {DISCOUNT_TYPE_LABELS[option]}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {type === 'PERCENTAGE' ? (
            <div>
              <Label htmlFor="coupon-percent">Percentage off</Label>
              <div className="mt-2 flex items-center gap-2">
                <Input
                  id="coupon-percent"
                  type="number"
                  min={1}
                  max={99}
                  value={percent}
                  onChange={(event) => setPercent(event.target.value)}
                />
                <span className="text-sm text-muted-foreground">%</span>
              </div>
              {percentOutOfRange ? (
                <p className="mt-1 text-xs text-destructive">Between 1 and 99.</p>
              ) : null}
            </div>
          ) : type === 'FIXED_AMOUNT' ? (
            <>
              <div>
                <Label htmlFor="coupon-amount">Amount off</Label>
                <Input
                  id="coupon-amount"
                  type="number"
                  min={0}
                  step="0.01"
                  className="mt-2"
                  value={amount}
                  onChange={(event) => setAmount(event.target.value)}
                />
              </div>
              <div>
                <Label htmlFor="coupon-currency">Currency</Label>
                <Input
                  id="coupon-currency"
                  className="mt-2 uppercase"
                  maxLength={3}
                  value={currency}
                  onChange={(event) => setCurrency(event.target.value.toUpperCase())}
                />
                {/* Not converted at checkout — refused. Worth saying while somebody picks one. */}
                <p className="mt-1 text-xs text-muted-foreground">
                  Refused on an order in another currency, not converted.
                </p>
              </div>
            </>
          ) : (
            <div className="flex items-end pb-2">
              <p className="text-xs text-muted-foreground">
                Takes the delivery charge to zero. Never touches the goods, so it does not change
                the tax.
              </p>
            </div>
          )}

          <div>
            <Label htmlFor="coupon-minimum">Minimum basket (optional)</Label>
            <Input
              id="coupon-minimum"
              type="number"
              min={0}
              step="0.01"
              className="mt-2"
              value={minimumBasket}
              onChange={(event) => setMinimumBasket(event.target.value)}
            />
          </div>

          <div>
            <Label htmlFor="coupon-until">Runs until (optional)</Label>
            <Input
              id="coupon-until"
              type="date"
              className="mt-2"
              value={validUntil}
              onChange={(event) => setValidUntil(event.target.value)}
            />
          </div>

          <div>
            <Label htmlFor="coupon-max">Total uses (optional)</Label>
            <Input
              id="coupon-max"
              type="number"
              min={1}
              className="mt-2"
              placeholder="Unlimited"
              value={maxRedemptions}
              onChange={(event) => setMaxRedemptions(event.target.value)}
            />
            {coupon && coupon.redemptionCount > 0 ? (
              // Refused server-side with the number in the message; said here first so an
              // operator does not have to find out by being told no.
              <p className="mt-1 text-xs text-muted-foreground">
                Already used {coupon.redemptionCount} times — cannot be set lower than that.
              </p>
            ) : null}
          </div>

          <div>
            <Label htmlFor="coupon-per-customer">Uses per customer (optional)</Label>
            <Input
              id="coupon-per-customer"
              type="number"
              min={1}
              className="mt-2"
              placeholder="Unlimited"
              value={perCustomerLimit}
              onChange={(event) => setPerCustomerLimit(event.target.value)}
            />
          </div>

          <label className="flex items-center gap-2 text-sm sm:col-span-2">
            <Checkbox
              checked={active}
              onCheckedChange={(checked) => setActive(checked === true)}
            />
            <span>
              Switched on
              <span className="block text-xs text-muted-foreground">
                Turning this off stops the code working immediately, whatever its window says.
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
            disabled={!code.trim() || percentOutOfRange || pending}
            onClick={() =>
              onSubmit({
                code: code.trim(),
                description: description.trim() || undefined,
                type,
                value,
                currency: type === 'FIXED_AMOUNT' ? currency.trim() : undefined,
                minimumBasket: minimumBasket ? Number(minimumBasket) : undefined,
                maxRedemptions: maxRedemptions ? Number(maxRedemptions) : undefined,
                perCustomerLimit: perCustomerLimit ? Number(perCustomerLimit) : undefined,
                // Midnight at the end of the chosen day, so "runs until the 5th" includes the 5th.
                validUntil: validUntil
                  ? new Date(`${validUntil}T23:59:59`).toISOString()
                  : undefined,
                active,
              })
            }
          >
            {coupon ? 'Save changes' : 'Create code'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
