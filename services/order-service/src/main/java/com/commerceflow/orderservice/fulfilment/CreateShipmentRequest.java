package com.commerceflow.orderservice.fulfilment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Opening a shipment.
 *
 * <p>Every field is optional. A warehouse frequently creates the shipment when it starts picking
 * and only knows the carrier and tracking number when the parcel is handed over — requiring them
 * up front would mean either lying in the form or not recording the shipment until later, and the
 * second loses the picking step entirely.
 */
@Schema(description = "A new shipment")
public record CreateShipmentRequest(

        @Schema(example = "PostNL") @Size(max = 100) String carrier,

        @Schema(example = "3SABCD1234567") @Size(max = 100) String trackingNumber,

        @Schema(description = "The carrier's own tracking page for this parcel")
        @Size(max = 500) String trackingUrl) {
}
