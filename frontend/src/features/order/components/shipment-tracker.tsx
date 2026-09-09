import { AlertTriangle, PackageCheck, Truck } from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { formatDate, formatDateTime } from '@/utils/format';

import {
  SHIPMENT_STATUS_LABELS,
  useOrderShipments,
  type Shipment,
} from '../shipments-api';

/**
 * Where a parcel is.
 *
 * <h2>Renders nothing until there is a parcel</h2>
 *
 * An order that has not been picked yet has no shipment, and that is the normal state for the
 * first day rather than something to apologise for. An empty "no tracking information" panel on
 * every fresh order is a panel that trains people to ignore this part of the page.
 *
 * <h2>The whole history, not just the latest line</h2>
 *
 * "The carrier says they tried on Tuesday" is a real conversation, and it cannot be had against a
 * single status. Every recorded event is shown, newest first.
 */
export function ShipmentTracker({ orderId }: { orderId: string }) {
  const shipments = useOrderShipments(orderId);

  if (!shipments.data || shipments.data.length === 0) {
    return null;
  }

  return (
    <div className="space-y-4">
      {shipments.data.map((shipment) => (
        <ShipmentCard key={shipment.id} shipment={shipment} />
      ))}
    </div>
  );
}

function ShipmentCard({ shipment }: { shipment: Shipment }) {
  const finished = shipment.status === 'DELIVERED';

  return (
    <Card className={shipment.late ? 'border-amber-500/40' : undefined}>
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2 text-base">
          {finished ? (
            <PackageCheck className="h-4 w-4 text-emerald-600" aria-hidden />
          ) : (
            <Truck className="h-4 w-4 text-muted-foreground" aria-hidden />
          )}
          {SHIPMENT_STATUS_LABELS[shipment.status]}

          {shipment.late ? (
            <Badge variant="outline" className="gap-1 border-amber-500/40 text-amber-700">
              <AlertTriangle className="h-3 w-3" aria-hidden />
              Later than we said
            </Badge>
          ) : null}
        </CardTitle>
      </CardHeader>

      <CardContent className="space-y-4 text-sm">
        <dl className="grid gap-2 sm:grid-cols-2">
          {shipment.carrier ? (
            <Row label="Carrier">{shipment.carrier}</Row>
          ) : null}

          {shipment.trackingNumber ? (
            <Row label="Tracking">
              {shipment.trackingUrl ? (
                <a
                  href={shipment.trackingUrl}
                  target="_blank"
                  rel="noreferrer noopener"
                  className="font-mono text-xs underline underline-offset-4"
                >
                  {shipment.trackingNumber}
                </a>
              ) : (
                <span className="font-mono text-xs">{shipment.trackingNumber}</span>
              )}
            </Row>
          ) : null}

          {/* What was promised at checkout, so a late parcel is late against a real commitment
              rather than against a guess made afterwards. */}
          {shipment.promisedBy && !finished ? (
            <Row label="Expected by">{formatDate(shipment.promisedBy)}</Row>
          ) : null}

          {shipment.deliveredAt ? (
            <Row label="Delivered">{formatDateTime(shipment.deliveredAt)}</Row>
          ) : null}
        </dl>

        {shipment.history.length > 0 ? (
          <div>
            <h4 className="mb-2 text-xs uppercase tracking-wide text-muted-foreground">
              History
            </h4>
            <ol className="space-y-2 border-l pl-4">
              {[...shipment.history].reverse().map((event, index) => (
                <li key={`${event.recordedAt}-${index}`} className="relative">
                  <span
                    aria-hidden
                    className="absolute -left-[21px] top-1.5 h-2 w-2 rounded-full bg-border"
                  />
                  <p className="text-sm">
                    {SHIPMENT_STATUS_LABELS[event.status]}
                    {event.location ? (
                      <span className="text-muted-foreground"> — {event.location}</span>
                    ) : null}
                  </p>
                  {event.note ? (
                    <p className="text-xs text-muted-foreground">{event.note}</p>
                  ) : null}
                  <p className="text-xs text-muted-foreground/80">
                    {formatDateTime(event.recordedAt)}
                  </p>
                </li>
              ))}
            </ol>
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex justify-between gap-3 sm:block">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="sm:mt-0.5">{children}</dd>
    </div>
  );
}
