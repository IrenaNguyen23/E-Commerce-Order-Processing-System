import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';

import { orderKeys } from '@/features/order/hooks';
import { api } from '@/services/api-client';
import { endpoints } from '@/services/endpoints';
import { normalizeError } from '@/services/error';
import type { PageResponse } from '@/types/api';

/**
 * Parcels.
 *
 * <h2>A shipment status is not an order status</h2>
 *
 * An order stays `COMPLETED` while its parcel goes from `PENDING` to `DELIVERED`. Two lifecycles
 * on two clocks: the order's is the saga's state machine, guarded so a late message cannot move it
 * backwards, and the parcel's is driven by people and carriers and legitimately does go backwards
 * — a delivery marked at the wrong building, a return.
 *
 * The UI shows both, and does not try to merge them into one word.
 */

export type ShipmentStatus =
  | 'PENDING'
  | 'PICKING'
  | 'DISPATCHED'
  | 'IN_TRANSIT'
  | 'ATTEMPTED'
  | 'DELIVERED'
  | 'RETURNED'
  | 'CANCELLED';

export const SHIPMENT_STATUS_LABELS: Record<ShipmentStatus, string> = {
  PENDING: 'Preparing',
  PICKING: 'Being packed',
  DISPATCHED: 'On its way',
  IN_TRANSIT: 'In transit',
  ATTEMPTED: 'Delivery attempted',
  DELIVERED: 'Delivered',
  RETURNED: 'Returned to us',
  CANCELLED: 'Cancelled',
};

export interface ShipmentEvent {
  status: ShipmentStatus;
  note: string | null;
  location: string | null;
  recordedAt: string;
}

export interface Shipment {
  id: string;
  orderId: string;
  orderNumber: string;
  status: ShipmentStatus;
  carrier: string | null;
  trackingNumber: string | null;
  /** Stored by the backend rather than built from the carrier name; carriers change formats. */
  trackingUrl: string | null;
  destination: string;
  /** When the customer was told to expect it, from the window quoted at checkout. */
  promisedBy: string | null;
  /** True when that date has passed and it has not arrived. */
  late: boolean;
  dispatchedAt: string | null;
  deliveredAt: string | null;
  history: ShipmentEvent[];
  createdAt: string;
  updatedAt: string;
}

/**
 * What a warehouse can set, and the order it is offered in.
 *
 * <p>Not every transition the backend allows: it accepts anything that is not nonsense, because
 * real parcels go backwards — a failed delivery returns to transit, a delivered one comes back.
 * The console offers the sensible next steps from where the parcel is, and keeps the rest
 * reachable rather than hiding them, because the odd cases are exactly when somebody needs them.
 */
export const NEXT_SHIPMENT_STATUSES: Record<ShipmentStatus, ShipmentStatus[]> = {
  PENDING: ['PICKING', 'DISPATCHED', 'CANCELLED'],
  PICKING: ['DISPATCHED', 'CANCELLED'],
  DISPATCHED: ['IN_TRANSIT', 'ATTEMPTED', 'DELIVERED', 'RETURNED'],
  IN_TRANSIT: ['ATTEMPTED', 'DELIVERED', 'RETURNED'],
  ATTEMPTED: ['IN_TRANSIT', 'DELIVERED', 'RETURNED'],
  DELIVERED: ['RETURNED'],
  RETURNED: [],
  CANCELLED: [],
};

/** Statuses a warehouse works through, in the order the queue offers them. */
export const SHIPMENT_QUEUE_STATUSES: ShipmentStatus[] = [
  'PENDING',
  'PICKING',
  'DISPATCHED',
  'IN_TRANSIT',
  'ATTEMPTED',
  'DELIVERED',
  'RETURNED',
  'CANCELLED',
];

export interface CreateShipmentPayload {
  carrier?: string;
  trackingNumber?: string;
  trackingUrl?: string;
}

export interface ShipmentEventPayload {
  status: ShipmentStatus;
  note?: string;
  location?: string;
  carrier?: string;
  trackingNumber?: string;
  trackingUrl?: string;
}

export const shipmentKeys = {
  all: ['shipments'] as const,
  forOrder: (orderId: string) => [...shipmentKeys.all, 'order', orderId] as const,
  queue: (status: ShipmentStatus, page: number) =>
    [...shipmentKeys.all, 'queue', status, page] as const,
};

export const shipmentApi = {
  forOrder: (orderId: string): Promise<Shipment[]> =>
    api.get<Shipment[]>(endpoints.shipments.forOrder(orderId)),

  track: (trackingNumber: string): Promise<Shipment> =>
    api.get<Shipment>(endpoints.shipments.track(trackingNumber)),

  queue: (status: ShipmentStatus, page: number, size = 20): Promise<PageResponse<Shipment>> =>
    api.get<PageResponse<Shipment>>(endpoints.shipments.queue, {
      params: { status, page, size },
    }),

  create: (orderId: string, payload: CreateShipmentPayload): Promise<Shipment> =>
    api.post<Shipment>(endpoints.shipments.forOrder(orderId), payload),

  recordEvent: (shipmentId: string, payload: ShipmentEventPayload): Promise<Shipment> =>
    api.post<Shipment>(endpoints.shipments.events(shipmentId), payload),
};

/**
 * Parcels for an order.
 *
 * Usually none — an order that has not been picked yet has no shipment, and that is not an error.
 * The caller renders nothing rather than an empty state, because "no tracking yet" is the normal
 * condition for the first day.
 */
export function useOrderShipments(orderId: string | undefined) {
  return useQuery({
    queryKey: shipmentKeys.forOrder(orderId ?? ''),
    queryFn: () => shipmentApi.forOrder(orderId!),
    enabled: Boolean(orderId),
  });
}

/** The warehouse queue: everything in one state, oldest first, which is the order to work it in. */
export function useShipmentQueue(status: ShipmentStatus, page: number) {
  return useQuery({
    queryKey: shipmentKeys.queue(status, page),
    queryFn: () => shipmentApi.queue(status, page),
    // A queue two people are working at once goes stale quickly, and picking a parcel somebody
    // else already dispatched wastes a trip to the shelf.
    staleTime: 10_000,
  });
}

/**
 * Anything that changes a parcel.
 *
 * <p>Invalidates the order queries too: an order's page shows its shipments, and the customer's
 * view of "where is my order" is the same data.
 */
function useShipmentMutation<TVariables>(
  mutationFn: (variables: TVariables) => Promise<Shipment>,
  successMessage: string,
) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: shipmentKeys.all });
      void queryClient.invalidateQueries({ queryKey: orderKeys.all });
      toast.success(successMessage);
    },
    onError: (error) => toast.error(normalizeError(error).message),
  });
}

export function useCreateShipment() {
  return useShipmentMutation(
    ({ orderId, payload }: { orderId: string; payload: CreateShipmentPayload }) =>
      shipmentApi.create(orderId, payload),
    'Shipment opened',
  );
}

export function useRecordShipmentEvent() {
  return useShipmentMutation(
    ({ shipmentId, payload }: { shipmentId: string; payload: ShipmentEventPayload }) =>
      shipmentApi.recordEvent(shipmentId, payload),
    'Recorded',
  );
}
