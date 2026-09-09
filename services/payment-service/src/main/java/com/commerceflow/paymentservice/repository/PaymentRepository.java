package com.commerceflow.paymentservice.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.commerceflow.paymentservice.entity.Payment;
import com.commerceflow.paymentservice.entity.PaymentStatus;

/** Persistence port for payments. */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByOrderId(UUID orderId);

    /** The webhook lookup: an acquirer event names its own reference, not our payment id. */
    Optional<Payment> findByGatewayReference(String gatewayReference);

    boolean existsByOrderId(UUID orderId);

    List<Payment> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Query("""
            SELECT p FROM Payment p
             WHERE (:userId IS NULL OR p.userId = :userId)
               AND (:status IS NULL OR p.status = :status)
            """)
    Page<Payment> search(@Param("userId") UUID userId,
                         @Param("status") PaymentStatus status,
                         Pageable pageable);

    long countByStatus(PaymentStatus status);
}
