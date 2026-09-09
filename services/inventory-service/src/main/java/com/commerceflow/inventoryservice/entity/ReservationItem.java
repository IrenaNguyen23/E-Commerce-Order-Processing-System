package com.commerceflow.inventoryservice.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** One line of a stock reservation. */
@Entity
@Table(name = "reservation_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private InventoryReservation reservation;

    /**
     * Which building these units are held in.
     *
     * <p>Recorded at reservation time and never recomputed. Releasing, confirming and restocking
     * all act on <em>this</em> building — working it out again later would be guessing, and the
     * guess would be wrong exactly when stock has moved since.
     *
     * <p>A line that had to be split across buildings becomes several of these rows for the same
     * product. That is visible rather than hidden, because it means several parcels.
     */
    @Column(name = "warehouse_id")
    private UUID warehouseId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "quantity", nullable = false)
    private int quantity;
}
