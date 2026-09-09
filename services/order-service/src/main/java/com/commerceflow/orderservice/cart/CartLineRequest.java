package com.commerceflow.orderservice.cart;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** A product and a quantity, for setting a line or merging a guest basket. */
@Schema(description = "A basket line")
public record CartLineRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "productId is required")
        UUID productId,

        @Schema(description = "Sets the quantity rather than adding to it. Zero removes the line.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @Min(value = 0, message = "quantity cannot be negative")
        int quantity) {
}
