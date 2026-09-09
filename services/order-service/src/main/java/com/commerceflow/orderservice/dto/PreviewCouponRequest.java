package com.commerceflow.orderservice.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Asking what a discount code would be worth.
 *
 * <p>The basket is sent rather than read from the server, because there is no server-side basket
 * yet. That is a real limitation and it has a real consequence: a client could ask about a basket
 * it does not have. It does not matter here, because this endpoint spends nothing and decides
 * nothing — the code is validated again, against the actual order, at checkout. The number the
 * customer is charged never comes from this call.
 */
@Schema(description = "A code and the basket to try it against")
public record PreviewCouponRequest(

        @Schema(example = "WELCOME10", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Enter a discount code")
        @Size(max = 40)
        String code,

        @Schema(description = "The goods at list price, before any discount",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @PositiveOrZero
        BigDecimal basketAmount,

        @Schema(description = "What delivery currently costs, so a free-delivery code can say "
                + "what it is worth")
        @PositiveOrZero
        BigDecimal shippingAmount,

        @Schema(example = "EUR", description = "Defaults to EUR")
        @Size(min = 3, max = 3)
        String currency) {
}
