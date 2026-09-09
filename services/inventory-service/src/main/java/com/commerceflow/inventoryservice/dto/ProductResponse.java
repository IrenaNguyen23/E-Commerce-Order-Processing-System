package com.commerceflow.inventoryservice.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Catalogue projection of a product together with its current stock position. */
@Schema(description = "Catalogue product")
public record ProductResponse(
        UUID id,
        @Schema(example = "CF-LAPTOP-001") String sku,
        String name,
        String description,
        @Schema(description = "Which section it is filed under; null when unfiled")
        UUID categoryId,
        @Schema(description = "The section's stable identifier. Filters and the tax rate card "
                + "key on this, never on the display name.", example = "computers")
        String categorySlug,
        @Schema(description = "The section's display name, which may be renamed at any time",
                example = "Computers")
        String category,
        @Schema(example = "1299.00") BigDecimal price,
        @Schema(example = "EUR") String currency,
        String imageUrl,
        boolean active,
        @Schema(description = "Units a new order may consume") int availableQuantity,
        @Schema(description = "Units held by orders whose saga is still running") int reservedQuantity,
        boolean inStock,
        @Schema(description = "Average of the published reviews, or null when there are none. "
                + "Null rather than zero: \"no reviews yet\" and \"reviewed, and terrible\" "
                + "are different things.")
        java.math.BigDecimal ratingAverage,
        @Schema(description = "How many published reviews the average is made of")
        int ratingCount,
        Instant createdAt,
        Instant updatedAt) {
}
