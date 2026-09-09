package com.commerceflow.inventoryservice.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What an operator gets back after releasing a hold by hand.
 *
 * @param unitsReturned how many units went back to available — the number worth checking against
 *     what the order actually asked for
 */
@Schema(description = "Result of releasing an inventory hold outside the saga")
public record ReservationReleaseResponse(
        UUID reservationId,
        UUID orderId,
        @Schema(example = "RELEASED") String status,
        @Schema(example = "3") int unitsReturned,
        @Schema(example = "RELEASED_BY_OPERATOR") String reason) {
}
