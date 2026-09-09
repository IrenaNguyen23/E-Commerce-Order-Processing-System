package com.commerceflow.inventoryservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Payload of {@code PUT /api/products/{id}/stock}. */
@Schema(description = "Back-office stock correction")
public record UpdateStockRequest(

        @Schema(example = "250", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Min(value = 0, message = "quantity cannot be negative")
        Integer quantity,

        @Schema(description = "How to apply the quantity", defaultValue = "SET")
        StockOperation operation,

        @Schema(description = "Audit note", example = "Stock count 2026-08-26")
        @Size(max = 255) String reason) {

    /** How {@link #quantity()} is applied to the current available stock. */
    public enum StockOperation {
        /** Replace the available quantity. */
        SET,
        /** Add to the available quantity, e.g. a delivery from the supplier. */
        INCREASE,
        /** Subtract from the available quantity, e.g. breakage. */
        DECREASE
    }

    public StockOperation operationOrDefault() {
        return operation == null ? StockOperation.SET : operation;
    }
}
