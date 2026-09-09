package com.commerceflow.inventoryservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Why an operator is releasing a hold by hand.
 *
 * <p>Optional, and recorded on the reservation. Worth filling in: the next person to read that
 * row will want to know whether the stock came back because the order was abandoned or because
 * someone was clearing an alert.
 */
@Schema(description = "Optional context for a manual inventory release")
public record ReleaseReservationRequest(
        @Size(max = 128)
        @Schema(example = "Saga orphaned by the 2026-08-27 deploy; order confirmed unpaid")
        String reason) {
}
