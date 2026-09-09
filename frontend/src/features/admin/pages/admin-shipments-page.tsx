import { PackageCheck } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';

import { PageHeader } from '@/components/common/page-header';
import { Pagination } from '@/components/common/pagination';
import { EmptyState, ErrorState } from '@/components/common/states';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { ShipmentCard } from '@/features/admin/components/shipment-panel';
import {
  SHIPMENT_QUEUE_STATUSES,
  SHIPMENT_STATUS_LABELS,
  useShipmentQueue,
  type ShipmentStatus,
} from '@/features/order/shipments-api';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { cn } from '@/utils/cn';

/**
 * The warehouse queue.
 *
 * <h2>Oldest first, and one state at a time</h2>
 *
 * A queue worked newest-first leaves a tail nobody ever reaches — and in a warehouse that tail is
 * somebody's order sitting on a shelf. The backend returns oldest first for exactly that reason,
 * and this screen does not offer a way to re-sort it.
 *
 * <h2>Why a tab per status rather than one list</h2>
 *
 * The states are different jobs. `PENDING` is "go and pick this", `ATTEMPTED` is "ring the
 * customer", `DELIVERED` is a record and not work at all. Mixing them into one list would mean
 * every person working it filters the same way every morning.
 *
 * <h2>Late parcels first, visually</h2>
 *
 * A parcel past the date the customer was promised is marked in every row. It is not re-sorted to
 * the top: the queue order is the fair order, and jumping the queue for the loudest failure is how
 * the tail grows.
 */
export default function AdminShipmentsPage() {
  const [status, setStatus] = useState<ShipmentStatus>('PENDING');
  const [page, setPage] = useState(0);

  const queue = useShipmentQueue(status, page);
  const parcels = queue.data?.content ?? [];

  function choose(next: ShipmentStatus) {
    setStatus(next);
    setPage(0);
  }

  return (
    <div>
      <PageHeader
        title="Fulfilment"
        description="Parcels waiting to be worked, oldest first."
        breadcrumbs={[{ label: 'Admin', to: paths.admin.dashboard }, { label: 'Fulfilment' }]}
      />

      <div className="mb-6 flex flex-wrap gap-2" role="tablist" aria-label="Shipment status">
        {SHIPMENT_QUEUE_STATUSES.map((option) => (
          <button
            key={option}
            type="button"
            role="tab"
            aria-selected={option === status}
            onClick={() => choose(option)}
            className={cn(
              'rounded-full border px-3 py-1 text-sm font-medium transition-colors',
              option === status
                ? 'border-transparent bg-primary text-primary-foreground'
                : 'text-muted-foreground hover:bg-muted',
            )}
          >
            {SHIPMENT_STATUS_LABELS[option]}
          </button>
        ))}
      </div>

      {queue.isError ? (
        <ErrorState error={normalizeError(queue.error)} onRetry={() => void queue.refetch()} />
      ) : queue.isLoading ? (
        <div className="space-y-3">
          {[0, 1, 2].map((row) => (
            <Skeleton key={row} className="h-40 w-full rounded-lg" />
          ))}
        </div>
      ) : parcels.length === 0 ? (
        <EmptyState
          icon={<PackageCheck className="h-10 w-10" />}
          title={`Nothing ${SHIPMENT_STATUS_LABELS[status].toLowerCase()}`}
          description={
            status === 'PENDING'
              ? 'Every order that has been paid for has a parcel on its way. New ones appear here as orders complete.'
              : 'No parcel is in this state right now.'
          }
        />
      ) : (
        <>
          <p className="mb-4 text-sm text-muted-foreground">
            {queue.data?.totalElements} {queue.data?.totalElements === 1 ? 'parcel' : 'parcels'}
          </p>

          <ul className="space-y-4">
            {parcels.map((parcel) => (
              <li key={parcel.id} className="space-y-2">
                <div className="flex flex-wrap items-center gap-2 text-sm">
                  <Link
                    to={paths.admin.order(parcel.orderId)}
                    className="font-medium hover:underline"
                  >
                    {parcel.orderNumber}
                  </Link>
                  <span className="text-muted-foreground">{parcel.destination}</span>
                  {parcel.late ? <Badge variant="destructive">Late</Badge> : null}
                </div>
                <ShipmentCard shipment={parcel} />
              </li>
            ))}
          </ul>

          {queue.data && queue.data.totalPages > 1 ? (
            <div className="mt-6">
              <Pagination
                page={page}
                totalPages={queue.data.totalPages}
                pageSize={queue.data.size}
                totalElements={queue.data.totalElements}
                onPageChange={setPage}
              />
            </div>
          ) : null}
        </>
      )}
    </div>
  );
}
