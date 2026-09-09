package com.commerceflow.inventoryservice.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The stock held for one order.
 *
 * <p>The unique index on {@code order_id} is the second line of defence for idempotency: even if
 * the ledger were bypassed, a redelivered {@code order.created} could not create a second hold.
 */
@Entity
@Table(name = "inventory_reservations", indexes = {
        @Index(name = "idx_reservation_status", columnList = "status")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryReservation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "order_number", length = 32)
    private String orderNumber;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReservationStatus status;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @Builder.Default
    private List<ReservationItem> items = new ArrayList<>();

    public void addItem(ReservationItem item) {
        item.setReservation(this);
        this.items.add(item);
    }

    public void markReleased(String releaseReason) {
        this.status = ReservationStatus.RELEASED;
        this.reason = releaseReason;
        this.updatedAt = Instant.now();
    }

    public void markConfirmed() {
        this.status = ReservationStatus.CONFIRMED;
        this.updatedAt = Instant.now();
    }

    /** Records that sold stock came back. Only meaningful from {@link ReservationStatus#CONFIRMED}. */
    public void markReturned(String returnReason) {
        this.status = ReservationStatus.RETURNED;
        this.reason = returnReason;
        this.updatedAt = Instant.now();
    }

    /** @return whether these units were written off as sold and could still be returned. */
    public boolean isSold() {
        return status == ReservationStatus.CONFIRMED;
    }

    public boolean isHoldingStock() {
        return status == ReservationStatus.RESERVED;
    }
}
