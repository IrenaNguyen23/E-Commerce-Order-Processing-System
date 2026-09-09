import { zodResolver } from '@hookform/resolvers/zod';
import { MapPin, Pencil, Plus, Star, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';

import { ConfirmDialog } from '@/components/common/confirm-dialog';
import { PageHeader } from '@/components/common/page-header';
import { EmptyState } from '@/components/common/states';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { addressSchema, type AddressValues } from '@/features/order/schemas';
import { paths } from '@/routes/paths';

import {
  useAddresses,
  useCreateAddress,
  useDeleteAddress,
  useMakeDefaultAddress,
  useUpdateAddress,
  type Address,
} from '../addresses-api';

/**
 * The address book.
 *
 * Server-side, so it follows the customer between devices — it used to live in `localStorage`,
 * which meant a phone and a laptop disagreed about where somebody lived.
 *
 * Deleting an address here cannot disturb an order: orders copy the address at checkout rather
 * than pointing at these rows. The delete confirmation says so, because it is the question
 * anybody hesitates over.
 */
export default function AddressesPage() {
  const { data: addresses = [], isLoading } = useAddresses();
  const createAddress = useCreateAddress();
  const updateAddress = useUpdateAddress();
  const deleteAddress = useDeleteAddress();
  const makeDefault = useMakeDefaultAddress();

  const [editing, setEditing] = useState<Address | null>(null);
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<Address | null>(null);

  const dialogOpen = creating || editing !== null;

  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader
        title="Addresses"
        description="Saved to your account and used to prefill checkout."
        breadcrumbs={[
          { label: 'Home', to: paths.home },
          { label: 'Profile', to: paths.profile },
          { label: 'Addresses' },
        ]}
        actions={
          <Button onClick={() => setCreating(true)}>
            <Plus aria-hidden />
            Add address
          </Button>
        }
      />

      {isLoading ? (
        <p className="text-sm text-muted-foreground">Loading your addresses...</p>
      ) : addresses.length === 0 ? (
        <EmptyState
          icon={<MapPin className="h-10 w-10" />}
          title="No saved addresses"
          description="Add one here, or tick “save this address” at checkout."
          action={
            <Button onClick={() => setCreating(true)}>
              <Plus aria-hidden />
              Add address
            </Button>
          }
        />
      ) : (
        <div className="space-y-3">
          {addresses.map((address) => (
            <Card key={address.id}>
              <CardContent className="flex flex-col gap-4 p-5 sm:flex-row sm:items-start sm:justify-between">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="font-medium">{address.label || 'Address'}</p>
                    {address.isDefault ? (
                      <Badge variant="secondary" className="gap-1">
                        <Star className="h-3 w-3 fill-current" aria-hidden />
                        Default
                      </Badge>
                    ) : null}
                  </div>
                  {/* Formatted by the backend, so this line reads identically here, on the
                      order confirmation and on the shipping label. */}
                  <p className="mt-1 text-sm text-muted-foreground">{address.formatted}</p>
                </div>

                <div className="flex shrink-0 gap-1">
                  {!address.isDefault ? (
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => makeDefault.mutate(address.id)}
                    >
                      Make default
                    </Button>
                  ) : null}
                  <Button
                    variant="ghost"
                    size="icon"
                    onClick={() => setEditing(address)}
                    aria-label={`Edit ${address.label || 'address'}`}
                  >
                    <Pencil aria-hidden />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    onClick={() => setDeleting(address)}
                    aria-label={`Delete ${address.label || 'address'}`}
                  >
                    <Trash2 aria-hidden />
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      <AddressDialog
        open={dialogOpen}
        address={editing}
        onClose={() => {
          setCreating(false);
          setEditing(null);
        }}
        pending={createAddress.isPending || updateAddress.isPending}
        onSubmit={(values) => {
          const payload = {
            label: values.label || undefined,
            recipientName: values.recipientName,
            phone: values.phone || undefined,
            line1: values.line1,
            line2: values.line2 || undefined,
            city: values.city,
            region: values.region || undefined,
            postalCode: values.postalCode || undefined,
            countryCode: values.countryCode,
          };

          if (editing) {
            updateAddress.mutate({ id: editing.id, payload });
          } else {
            createAddress.mutate(payload);
          }
          setCreating(false);
          setEditing(null);
        }}
      />

      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title="Delete this address?"
        description={
          <>
            <span className="block">{deleting?.formatted ?? ''}</span>
            <span className="mt-2 block">
              It will no longer prefill at checkout. Orders already placed are unaffected — each
              one kept its own copy of the address it was sent to.
            </span>
          </>
        }
        confirmLabel="Delete"
        destructive
        onConfirm={() => {
          if (deleting) {
            deleteAddress.mutate(deleting.id);
          }
          setDeleting(null);
        }}
      />
    </div>
  );
}

function AddressDialog({
  open,
  address,
  pending,
  onClose,
  onSubmit,
}: {
  open: boolean;
  address: Address | null;
  pending: boolean;
  onClose: () => void;
  onSubmit: (values: AddressValues) => void;
}) {
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<AddressValues>({
    resolver: zodResolver(addressSchema),
    values: address
      ? {
          label: address.label ?? '',
          recipientName: address.recipientName,
          line1: address.line1,
          line2: address.line2 ?? '',
          city: address.city,
          region: address.region ?? '',
          postalCode: address.postalCode ?? '',
          countryCode: address.countryCode,
          phone: address.phone ?? '',
        }
      : {
          label: '',
          recipientName: '',
          line1: '',
          line2: '',
          city: '',
          region: '',
          postalCode: '',
          countryCode: '',
          phone: '',
        },
  });

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) {
          reset();
          onClose();
        }
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{address ? 'Edit address' : 'Add address'}</DialogTitle>
        </DialogHeader>

        <form
          onSubmit={handleSubmit((values) => {
            onSubmit(values);
            reset();
          })}
          className="grid gap-4 sm:grid-cols-2"
          noValidate
        >
          <DialogField
            id="label"
            label="Label"
            optional
            placeholder="Home, Office…"
            className="sm:col-span-2"
            error={errors.label?.message}
            {...register('label')}
          />
          <DialogField
            id="recipientName"
            label="Recipient"
            className="sm:col-span-2"
            error={errors.recipientName?.message}
            {...register('recipientName')}
          />
          <DialogField
            id="line1"
            label="Street and number"
            className="sm:col-span-2"
            error={errors.line1?.message}
            {...register('line1')}
          />
          <DialogField
            id="line2"
            label="Apartment, suite"
            optional
            className="sm:col-span-2"
            error={errors.line2?.message}
            {...register('line2')}
          />
          <DialogField
            id="postalCode"
            label="Postal code"
            optional
            error={errors.postalCode?.message}
            {...register('postalCode')}
          />
          <DialogField id="city" label="City" error={errors.city?.message} {...register('city')} />
          <DialogField
            id="region"
            label="Region"
            optional
            error={errors.region?.message}
            {...register('region')}
          />
          <DialogField
            id="countryCode"
            label="Country code"
            placeholder="NL"
            maxLength={2}
            error={errors.countryCode?.message}
            {...register('countryCode')}
          />
          <DialogField
            id="phone"
            label="Phone"
            optional
            type="tel"
            error={errors.phone?.message}
            {...register('phone')}
          />

          <DialogFooter className="sm:col-span-2">
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                reset();
                onClose();
              }}
            >
              Cancel
            </Button>
            <Button type="submit" loading={pending}>
              {address ? 'Save changes' : 'Add address'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

const DialogField = ({
  id,
  label,
  error,
  optional,
  className,
  ...props
}: React.InputHTMLAttributes<HTMLInputElement> & {
  id: string;
  label: string;
  error?: string;
  optional?: boolean;
}) => (
  <div className={className}>
    <Label htmlFor={id}>
      {label}
      {optional ? <span className="ml-1 text-muted-foreground">(optional)</span> : null}
    </Label>
    <Input id={id} className="mt-2" aria-invalid={Boolean(error)} {...props} />
    {error ? <p className="mt-1 text-sm text-destructive">{error}</p> : null}
  </div>
);
