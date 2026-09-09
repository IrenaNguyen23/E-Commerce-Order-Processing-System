package com.commerceflow.orderservice.pricing;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A priced delivery option, as offered to the customer and as stored on the order.
 *
 * <p>{@code minDays}/{@code maxDays} are a range because that is what delivery honestly is. A
 * single date would be a promise nobody in this system is in a position to make — there is no
 * carrier integration behind it.
 */
@Schema(description = "A priced delivery option")
public record ShippingQuote(

        ShippingMethod method,

        @Schema(description = "What this method costs for this basket; zero when free")
        BigDecimal amount,

        String currency,

        @Schema(description = "Fastest realistic working days; 0 for collection")
        int minDays,

        @Schema(description = "Slowest realistic working days; 0 for collection")
        int maxDays,

        @Schema(description = "True when a threshold or the method itself made it free")
        boolean free,

        @Schema(description = "What to show next to the price, e.g. \"2-4 working days\"")
        String description) {
}
