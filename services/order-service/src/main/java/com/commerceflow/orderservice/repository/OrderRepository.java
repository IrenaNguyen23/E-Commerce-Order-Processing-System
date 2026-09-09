package com.commerceflow.orderservice.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.commerceflow.orderservice.entity.Order;
import com.commerceflow.orderservice.entity.OrderStatus;

/** Command-side persistence port for the order aggregate. */
@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByOrderNumber(String orderNumber);

    Optional<Order> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    boolean existsByOrderNumber(String orderNumber);

    long countByStatus(OrderStatus status);

    /**
     * Next value of the order-number sequence.
     *
     * <p>A database sequence rather than a count or a timestamp: it is monotonic, gap tolerant and
     * safe across replicas without any coordination.
     */
    @Query(value = "SELECT nextval('order_number_seq')", nativeQuery = true)
    long nextOrderSequence();

    /** Every order a customer placed. Used by the erasure listener. */
    java.util.List<Order> findByUserId(java.util.UUID userId);
}
