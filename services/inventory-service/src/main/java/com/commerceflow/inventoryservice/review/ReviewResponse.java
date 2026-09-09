package com.commerceflow.inventoryservice.review;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A review as returned by the API.
 *
 * <p>Carries no user id. A product page listing who wrote what, by account id, hands anyone
 * scraping it a way to follow one customer's purchases across the catalogue.
 */
@Schema(description = "A product review")
public record ReviewResponse(
        UUID id,
        UUID productId,
        @Schema(description = "The author as they chose to be shown", example = "Ada L.")
        String authorName,
        int rating,
        String title,
        String body,
        @Schema(description = "PENDING, PUBLISHED or REJECTED. Only the author and an "
                + "administrator ever see anything but PUBLISHED.")
        ReviewStatus status,
        @Schema(description = "True when the author had bought this product when they wrote it")
        boolean verifiedPurchase,
        @Schema(description = "Why it was rejected. Shown to its author, and to nobody else.")
        String moderationNote,
        Instant createdAt,
        Instant updatedAt) {
}
