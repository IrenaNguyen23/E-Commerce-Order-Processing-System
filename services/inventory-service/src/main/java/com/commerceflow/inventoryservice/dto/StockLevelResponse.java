package com.commerceflow.inventoryservice.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How much of one product is in one building.
 *
 * <p>This is the authoritative figure. The {@code availableQuantity} on a product is the sum of
 * these across every building, kept as a summary so a listing page does not have to add them up
 * per tile.
 */
@Schema(description = "Stock of one product in one warehouse")
public record StockLevelResponse(
        UUID warehouseId,
        UUID productId,
        String sku,
        @Schema(description = "Units a new order may take from this building")
        int availableQuantity,
        @Schema(description = "Units held here by an order whose saga is still running")
        int reservedQuantity,
        @Schema(description = "Per-building reorder point. A shop can be short in Milan and fine "
                + "in Amsterdam, which a single global figure cannot express.")
        int reorderLevel,
        boolean belowReorderLevel,
        Instant updatedAt) {
}
