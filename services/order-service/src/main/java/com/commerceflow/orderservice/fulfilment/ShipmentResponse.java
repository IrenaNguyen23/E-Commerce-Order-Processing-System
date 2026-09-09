package com.commerceflow.orderservice.fulfilment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A parcel, as a customer or an operator sees it.
 *
 * <p>Carries the whole event history rather than just the current state. "When did this ship?" and
 * "the carrier says they tried on Tuesday" are the two questions support actually gets, and a
 * single status column answers neither.
 */
@Schema(description = "A shipment")
public record ShipmentResponse(
        UUID id,
        UUID orderId,
        String orderNumber,
        ShipmentStatus status,
        String carrier,
        String trackingNumber,
        @Schema(description = "Stored rather than built from the carrier name, so a carrier "
                + "changing its URL format cannot break every historical link")
        String trackingUrl,
        String destination,
        @Schema(description = "When the customer was told to expect it, from the window quoted "
                + "at checkout")
        Instant promisedBy,
        @Schema(description = "True when that date has passed and it has not arrived")
        boolean late,
        Instant dispatchedAt,
        Instant deliveredAt,
        List<ShipmentEventResponse> history,
        Instant createdAt,
        Instant updatedAt) {

    static ShipmentResponse of(Shipment shipment) {
        return new ShipmentResponse(shipment.getId(), shipment.getOrderId(),
                shipment.getOrderNumber(), shipment.getStatus(), shipment.getCarrier(),
                shipment.getTrackingNumber(), shipment.getTrackingUrl(),
                shipment.getDestination(), shipment.getPromisedBy(), shipment.isLate(),
                shipment.getDispatchedAt(), shipment.getDeliveredAt(),
                shipment.getEvents().stream().map(ShipmentEventResponse::of).toList(),
                shipment.getCreatedAt(), shipment.getUpdatedAt());
    }
}
