package com.commerceflow.orderservice.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** One requested line of {@code POST /api/orders}. */
@Schema(description = "Requested order line")
public record OrderItemRequest(

        @Schema(example = "11111111-1111-1111-1111-111111111101",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "productId is required")
        UUID productId,

        @Schema(example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "quantity is required")
        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 999, message = "quantity may not exceed 999")
        Integer quantity) {
}
