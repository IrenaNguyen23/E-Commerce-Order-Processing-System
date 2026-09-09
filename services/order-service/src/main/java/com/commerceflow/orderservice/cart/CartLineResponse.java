package com.commerceflow.orderservice.cart;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One basket line, with today's catalogue data attached.
 *
 * <p>Nothing here is stored. The price, the name and the picture are read on every request, so a
 * basket always shows what things cost now — the opposite of an order line, which freezes them.
 */
@Schema(description = "A basket line")
public record CartLineResponse(
        UUID productId,
        String sku,
        String name,
        String imageUrl,
        int quantity,
        @Schema(description = "Today's price, read live from the catalogue")
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        String currency,
        @Schema(description = "Units a new order could take right now")
        int availableQuantity,
        boolean available,
        @Schema(description = "What is wrong with this line, in words for the customer: "
                + "\"Out of stock\", \"Only 2 left\", \"No longer for sale\". Null when fine.")
        String issue,
        Instant addedAt) {

    /**
     * A line whose product has gone from the catalogue entirely.
     *
     * <p>Kept and marked rather than dropped. A line that vanishes without explanation is
     * indistinguishable from one the customer removed themselves, and the question "where did that
     * go?" has no answer anywhere.
     */
    static CartLineResponse unavailable(UUID productId, int quantity, String issue) {
        return new CartLineResponse(productId, null, "Unavailable product", null, quantity,
                BigDecimal.ZERO, BigDecimal.ZERO, null, 0, false, issue, null);
    }
}
