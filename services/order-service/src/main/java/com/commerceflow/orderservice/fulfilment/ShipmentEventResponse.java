package com.commerceflow.orderservice.fulfilment;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One entry in a parcel's history.
 *
 * <p>Carries no operator id. A customer tracking their parcel does not need to know which member
 * of warehouse staff scanned it, and publishing internal identifiers on a customer-facing screen
 * is how they end up somewhere they were never meant to be.
 */
@Schema(description = "Something that happened to a shipment")
public record ShipmentEventResponse(
        ShipmentStatus status,
        String note,
        String location,
        Instant recordedAt) {

    static ShipmentEventResponse of(ShipmentEvent event) {
        return new ShipmentEventResponse(event.getStatus(), event.getNote(), event.getLocation(),
                event.getRecordedAt());
    }
}
