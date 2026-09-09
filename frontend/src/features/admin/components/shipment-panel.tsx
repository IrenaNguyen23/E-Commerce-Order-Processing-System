import { PackagePlus, Truck } from 'lucide-react';
import { useState } from 'react';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/textarea';
import {
  NEXT_SHIPMENT_STATUSES,
  SHIPMENT_STATUS_LABELS,
  useCreateShipment,
  useOrderShipments,
  useRecordShipmentEvent,
  type Shipment,
  type ShipmentStatus,
} from '@/features/order/shipments-api';
import { cn } from '@/utils/cn';
import { formatDateTime } from '@/utils/format';

/**
 * Opening a parcel and moving it along, from the order it belongs to.
 *
 * <h2>A shipment is not an order status</h2>
 *
 * The order stays `COMPLETED` while the parcel goes from `PENDING` to `DELIVERED`. Two lifecycles
 * on two clocks, and this panel drives the second one without touching the first.
 *
 * <h2>Why the buttons are transitions, not a dropdown of every status</h2>
 *
 * A free choice of eight statuses invites the wrong one. The buttons offer what can sensibly
 * happen next from where the parcel actually is — and the backend still validates, because a
 * console is a convenience and not an authority.
 *
 * <h2>What emails the customer</h2>
 *
 * Dispatch, a failed attempt, delivery and a return each send a message. Picking does not, on
 * purpose: an email for every internal state change teaches people to ignore mail from the shop.
 * The buttons say so, because somebody clicking `DISPATCHED` should know it is not a private note.
 */

/** The transitions that reach the customer's inbox. */
const NOTIFYING: ShipmentStatus[] = ['DISPATCHED', 'ATTEMPTED', 'DELIVERED', 'RETURNED'];

interface ShipmentPanelProps {
  orderId: string;
  /** Shipments may only be opened once the saga is finished with the order. */
  orderStatus: string;
}

export function ShipmentPanel({ orderId, orderStatus }: ShipmentPanelProps) {
  const shipments = useOrderShipments(orderId);
  const createShipment = useCreateShipment();

  const [carrier, setCarrier] = useState('');
  const [trackingNumber, setTrackingNumber] = useState('');

  if (shipments.isLoading) {
    return <Skeleton className="h-32 w-full rounded-lg" />;
  }

  const parcels = shipments.data ?? [];

  // Only a finished order may be dispatched: an order still being paid for holds stock that is
  // reserved rather than sold, and sending against a reservation means shipping something the
  // shop may still have to refund. The backend refuses it; saying so here avoids the round trip.
  const canOpen = orderStatus === 'COMPLETED';

  return (
    <div className="space-y-4">
      {parcels.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No parcel yet. That is the normal state until the warehouse starts picking.
        </p>
      ) : (
        <ul className="space-y-4">
          {parcels.map((parcel) => (
            <li key={parcel.id}>
              <ShipmentCard shipment={parcel} />
            </li>
          ))}
        </ul>
      )}

      {canOpen ? (
        <form
          className="space-y-3 rounded-lg border border-dashed p-4"
          onSubmit={(event) => {
            event.preventDefault();
            createShipment.mutate(
              {
                orderId,
                payload: {
                  carrier: carrier.trim() || undefined,
                  trackingNumber: trackingNumber.trim() || undefined,
                },
              },
              {
                onSuccess: () => {
                  setCarrier('');
                  setTrackingNumber('');
                },
              },
            );
          }}
        >
          <p className="text-sm font-medium">Open another parcel</p>
          <p className="text-xs text-muted-foreground">
            Carrier and tracking number are optional — a warehouse usually opens the shipment when
            it starts picking and only learns them at hand-over.
          </p>

          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor="new-carrier">Carrier</Label>
              <Input
                id="new-carrier"
                placeholder="PostNL"
                value={carrier}
                onChange={(event) => setCarrier(event.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="new-tracking">Tracking number</Label>
              <Input
                id="new-tracking"
                placeholder="3SABCD1234567"
                value={trackingNumber}
                onChange={(event) => setTrackingNumber(event.target.value)}
              />
            </div>
          </div>

          <Button type="submit" size="sm" disabled={createShipment.isPending}>
            <PackagePlus className="mr-2 h-4 w-4" />
            {createShipment.isPending ? 'Opening…' : 'Open shipment'}
          </Button>
        </form>
      ) : (
        <p className="text-xs text-muted-foreground">
          A parcel can only be opened once the order is <strong>COMPLETED</strong>. This one is{' '}
          {orderStatus}: its stock is still reserved rather than sold.
        </p>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// One parcel
// ---------------------------------------------------------------------------------------------

export function ShipmentCard({ shipment }: { shipment: Shipment }) {
  const recordEvent = useRecordShipmentEvent();

  const [note, setNote] = useState('');
  const [location, setLocation] = useState('');
  const [carrier, setCarrier] = useState(shipment.carrier ?? '');
  const [trackingNumber, setTrackingNumber] = useState(shipment.trackingNumber ?? '');

  const next = NEXT_SHIPMENT_STATUSES[shipment.status];

  function advance(status: ShipmentStatus) {
    recordEvent.mutate(
      {
        shipmentId: shipment.id,
        payload: {
          status,
          note: note.trim() || undefined,
          location: location.trim() || undefined,
          // Sent with every event so the details can be filled in at the moment they are known —
          // which for a tracking number is hand-over, not when the parcel was opened.
          carrier: carrier.trim() || undefined,
          trackingNumber: trackingNumber.trim() || undefined,
        },
      },
      { onSuccess: () => { setNote(''); setLocation(''); } },
    );
  }

  return (
    <div className="rounded-lg border">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b p-4">
        <div className="flex flex-wrap items-center gap-2">
          <Truck className="h-4 w-4 text-muted-foreground" />
          <Badge variant={shipment.late ? 'destructive' : 'secondary'}>
            {SHIPMENT_STATUS_LABELS[shipment.status]}
          </Badge>
          {shipment.late ? (
            <span className="text-xs text-destructive">
              past the {formatDateTime(shipment.promisedBy)} it was promised for
            </span>
          ) : shipment.promisedBy ? (
            <span className="text-xs text-muted-foreground">
              promised by {formatDateTime(shipment.promisedBy)}
            </span>
          ) : null}
        </div>

        {shipment.trackingNumber ? (
          <code className="text-xs text-muted-foreground">{shipment.trackingNumber}</code>
        ) : null}
      </div>

      <div className="space-y-4 p-4">
        {next.length > 0 ? (
          <div className="space-y-3">
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-2">
                <Label htmlFor={`carrier-${shipment.id}`}>Carrier</Label>
                <Input
                  id={`carrier-${shipment.id}`}
                  placeholder="PostNL"
                  value={carrier}
                  onChange={(event) => setCarrier(event.target.value)}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor={`tracking-${shipment.id}`}>Tracking number</Label>
                <Input
                  id={`tracking-${shipment.id}`}
                  placeholder="3SABCD1234567"
                  value={trackingNumber}
                  onChange={(event) => setTrackingNumber(event.target.value)}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor={`location-${shipment.id}`}>Where</Label>
                <Input
                  id={`location-${shipment.id}`}
                  placeholder="Amsterdam depot"
                  value={location}
                  onChange={(event) => setLocation(event.target.value)}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor={`note-${shipment.id}`}>Note</Label>
                <Textarea
                  id={`note-${shipment.id}`}
                  rows={1}
                  placeholder="Left with a neighbour"
                  value={note}
                  onChange={(event) => setNote(event.target.value)}
                />
              </div>
            </div>

            <div className="flex flex-wrap gap-2">
              {next.map((status) => (
                <Button
                  key={status}
                  size="sm"
                  variant={status === 'CANCELLED' ? 'destructive' : 'outline'}
                  disabled={recordEvent.isPending}
                  onClick={() => advance(status)}
                >
                  {SHIPMENT_STATUS_LABELS[status]}
                  {NOTIFYING.includes(status) ? (
                    <span className="ml-2 text-xs opacity-70">emails</span>
                  ) : null}
                </Button>
              ))}
            </div>

            <p className="text-xs text-muted-foreground">
              Marked <span className="font-medium">emails</span> means the customer is told. The
              note and location go into the parcel&apos;s history either way.
            </p>
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">
            {shipment.status === 'CANCELLED'
              ? 'This parcel was cancelled. Open a new one if it still needs to go.'
              : 'This parcel is finished. Nothing further to record.'}
          </p>
        )}

        {/* History, newest last — a parcel's story reads forwards. */}
        {shipment.history.length > 0 ? (
          <ol className="space-y-2 border-t pt-4">
            {shipment.history.map((event, index) => (
              <li
                key={`${event.recordedAt}-${index}`}
                className={cn('flex flex-wrap gap-x-2 text-xs', index === shipment.history.length - 1
                  ? 'text-foreground'
                  : 'text-muted-foreground')}
              >
                <time dateTime={event.recordedAt} className="tabular-nums">
                  {formatDateTime(event.recordedAt)}
                </time>
                <span className="font-medium">{SHIPMENT_STATUS_LABELS[event.status]}</span>
                {event.location ? <span>· {event.location}</span> : null}
                {event.note ? <span>· {event.note}</span> : null}
              </li>
            ))}
          </ol>
        ) : null}
      </div>
    </div>
  );
}
