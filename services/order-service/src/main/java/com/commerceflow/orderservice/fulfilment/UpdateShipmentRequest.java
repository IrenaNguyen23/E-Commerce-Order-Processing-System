package com.commerceflow.orderservice.fulfilment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Recording what has happened to a parcel.
 *
 * <p>The carrier and tracking fields are here as well as on creation because they are usually
 * learnt at dispatch rather than at picking. Sending them as {@code null} leaves them alone;
 * sending a blank string clears them.
 */
@Schema(description = "A shipment update")
public record UpdateShipmentRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "PENDING, PICKING, DISPATCHED, IN_TRANSIT, ATTEMPTED, DELIVERED, "
                        + "RETURNED or CANCELLED")
        @NotNull(message = "status is required")
        ShipmentStatus status,

        @Schema(description = "What to show the customer: \"Left our warehouse\", "
                + "\"Nobody home, card left\"")
        @Size(max = 500) String note,

        @Schema(example = "Amsterdam depot") @Size(max = 150) String location,

        @Size(max = 100) String carrier,
        @Size(max = 100) String trackingNumber,
        @Size(max = 500) String trackingUrl) {
}
