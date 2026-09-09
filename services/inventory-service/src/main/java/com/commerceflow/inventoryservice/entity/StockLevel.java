package com.commerceflow.inventoryservice.entity;

import java.time.Instant;
import java.util.UUID;

import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ErrorCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * How much of one product is in one warehouse.
 *
 * <h2>This is the truth. {@link InventoryItem} is a summary of it.</h2>
 *
 * <p>Worth stating plainly, because two tables holding stock numbers is exactly the shape that
 * causes overselling. The rule is: <b>every change happens here first, and the product-level
 * total is recomputed from these rows in the same transaction.</b> Nothing writes a total
 * directly, and nothing reserves against one.
 *
 * <p>The summary exists because a product listing shows availability on every tile, and summing
 * a warehouse table per tile is the same problem as averaging reviews per tile. It is a cache
 * with a single writer and a transactional guarantee, not a second opinion.
 *
 * <h2>Reserved is not deducted from available</h2>
 *
 * <p>Same two-counter model as the product-level summary, for the same reason: a reservation has
 * to be reversible. Deducting on reserve and adding back on release loses the distinction between
 * "sold" and "held", and a compensation that runs twice then invents stock.
 */
@Entity
@Table(name = "stock_levels", indexes = {
        @Index(name = "idx_stock_levels_product", columnList = "product_id"),
        @Index(name = "idx_stock_levels_warehouse", columnList = "warehouse_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockLevel {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    /** Units a new order may take from this building. */
    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    /** Units held here by an order whose saga is still running. */
    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    /** Per-building reorder point. A shop can be short in Milan and fine in Amsterdam. */
    @Column(name = "reorder_level", nullable = false)
    @Builder.Default
    private int reorderLevel = 0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public boolean canReserve(int quantity) {
        return quantity > 0 && availableQuantity >= quantity;
    }

    /** Moves units from available to held. */
    public void reserve(int quantity) {
        if (!canReserve(quantity)) {
            throw new ConflictException(ErrorCode.INSUFFICIENT_STOCK,
                    "Only " + availableQuantity + " unit(s) here, needed " + quantity);
        }
        this.availableQuantity -= quantity;
        this.reservedQuantity += quantity;
        this.updatedAt = Instant.now();
    }

    /**
     * Gives held units back.
     *
     * <p>Clamped at zero rather than throwing. A release for more than is held means an earlier
     * message was processed twice, and refusing here would leave the saga unable to finish
     * compensating — stranding stock permanently to protect a counter that is already wrong.
     */
    public void release(int quantity) {
        int returning = Math.min(quantity, reservedQuantity);
        this.reservedQuantity -= returning;
        this.availableQuantity += returning;
        this.updatedAt = Instant.now();
    }

    /** The goods left the building. Held units stop being held and do not come back. */
    public void confirm(int quantity) {
        this.reservedQuantity = Math.max(0, reservedQuantity - quantity);
        this.updatedAt = Instant.now();
    }

    /** Sold goods coming back onto the shelf, after a cancellation. */
    public void restock(int quantity) {
        this.availableQuantity += Math.max(0, quantity);
        this.updatedAt = Instant.now();
    }

    /** A back-office correction. Never touches held units; see {@link InventoryItem}. */
    public void setAvailable(int quantity) {
        this.availableQuantity = Math.max(0, quantity);
        this.updatedAt = Instant.now();
    }

    public boolean isBelowReorderLevel() {
        return availableQuantity <= reorderLevel;
    }
}
