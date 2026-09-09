package com.commerceflow.orderservice.fulfilment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {

    List<Shipment> findByOrderId(UUID orderId);

    Page<Shipment> findByStatus(ShipmentStatus status, Pageable pageable);

    Page<Shipment> findByUserId(UUID userId, Pageable pageable);

    Optional<Shipment> findByTrackingNumber(String trackingNumber);
}
