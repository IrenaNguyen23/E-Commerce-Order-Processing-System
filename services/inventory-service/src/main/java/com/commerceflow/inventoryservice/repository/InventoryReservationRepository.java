package com.commerceflow.inventoryservice.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.commerceflow.inventoryservice.entity.InventoryReservation;
import com.commerceflow.inventoryservice.entity.ReservationStatus;

/** Persistence port for stock reservations. */
@Repository
public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    Optional<InventoryReservation> findByOrderId(UUID orderId);

    long countByStatus(ReservationStatus status);

    /**
     * Holds that have been sitting on stock too long, oldest first.
     *
     * <p>Paged so one sweep during an outage — exactly when this list is longest — cannot pull an
     * unbounded result set into memory.
     */
    List<InventoryReservation> findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            ReservationStatus status, Instant before, Pageable pageable);

    /** How many holds are stale, for the gauge. Counted rather than listed. */
    long countByStatusAndCreatedAtBefore(ReservationStatus status, Instant before);
}
