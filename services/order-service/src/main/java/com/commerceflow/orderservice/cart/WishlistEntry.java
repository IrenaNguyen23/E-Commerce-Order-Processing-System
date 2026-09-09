package com.commerceflow.orderservice.cart;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A saved item, priced from today's catalogue.
 *
 * <p>Live rather than remembered, deliberately. The most useful thing a wishlist does is let
 * somebody notice that a thing they wanted has come down in price, and a stored price would hide
 * exactly that.
 */
@Schema(description = "A wishlist item")
public record WishlistEntry(
        UUID productId,
        String sku,
        String name,
        String imageUrl,
        @Schema(description = "Today's price, not the price when it was saved")
        BigDecimal price,
        String currency,
        @Schema(description = "Whether it could be bought right now")
        boolean purchasable,
        int availableQuantity,
        Instant addedAt) {

    /** Something that has since left the catalogue. Kept and marked, never silently dropped. */
    static WishlistEntry unavailable(UUID productId, Instant addedAt) {
        return new WishlistEntry(productId, null, "No longer available", null, null, null,
                false, 0, addedAt);
    }
}
