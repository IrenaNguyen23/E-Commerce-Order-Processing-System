package com.commerceflow.inventoryservice.entity;

import java.time.Instant;
import java.util.UUID;

import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ErrorCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Stock ledger for one product, keyed by the product id (shared primary key).
 *
 * <p>Two counters rather than one: {@code availableQuantity} is what a new order may consume,
 * {@code reservedQuantity} is what earlier orders are holding while their saga runs. Keeping them
 * apart is what makes the compensation ({@code inventory.released}) exact rather than approximate.
 */
@Entity
@Table(name = "inventory_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryItem {

    @Id
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Column(name = "reorder_level", nullable = false)
    private int reorderLevel;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public boolean canReserve(int quantity) {
        return quantity > 0 && availableQuantity >= quantity;
    }

    /** Moves stock from available to reserved. */
    public void reserve(int quantity) {
        if (!canReserve(quantity)) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK,
                    "SKU " + sku + " has " + availableQuantity + " available but " + quantity
                            + " were requested");
        }
        this.availableQuantity -= quantity;
        this.reservedQuantity += quantity;
        this.updatedAt = Instant.now();
    }

    /** Compensation: moves stock back from reserved to available. */
    public void release(int quantity) {
        int effective = Math.min(quantity, reservedQuantity);
        this.reservedQuantity -= effective;
        this.availableQuantity += effective;
        this.updatedAt = Instant.now();
    }

    /** The order shipped: the reservation becomes a permanent deduction. */
    /**
     * Puts sold stock back on the shelf.
     *
     * <p>Not the same as {@link #release(int)}: a release moves units from held back to
     * available, whereas these units were already deducted from both counters when the sale was
     * confirmed. Calling release here would credit a hold that no longer exists and leave
     * {@code reservedQuantity} negative.
     */
    public void restock(int quantity) {
        this.availableQuantity += quantity;
        this.updatedAt = Instant.now();
    }

    public void confirm(int quantity) {
        this.reservedQuantity -= Math.min(quantity, reservedQuantity);
        this.updatedAt = Instant.now();
    }

    /** Absolute correction from the back office. */
    public void setAvailable(int quantity) {
        if (quantity < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Stock quantity cannot be negative");
        }
        this.availableQuantity = quantity;
        this.updatedAt = Instant.now();
    }

    public boolean isBelowReorderLevel() {
        return availableQuantity <= reorderLevel;
    }
}
