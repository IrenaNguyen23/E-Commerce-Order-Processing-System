package com.commerceflow.orderservice.cart;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A basket, priced from the live catalogue.
 *
 * <p>{@code total} covers only the lines that can actually be bought. A basket containing a
 * withdrawn product shows a total for what remains, rather than a figure that includes something
 * the customer cannot have — the alternative is a number on the basket page that the checkout will
 * not honour.
 *
 * <p>{@code checkoutable} is the single answer to "can the Checkout button be enabled". Working
 * that out on the client means every client works it out, and one of them eventually gets it
 * wrong.
 */
@Schema(description = "The customer's basket")
public record CartResponse(
        UUID id,
        List<CartLineResponse> lines,
        @Schema(description = "Units across every line, for a header badge")
        int totalQuantity,
        @Schema(description = "Value of the lines that can be bought, before tax and delivery")
        BigDecimal total,
        String currency,
        @Schema(description = "False when any line has a problem, or the basket is empty")
        boolean checkoutable,
        Instant updatedAt) {
}
