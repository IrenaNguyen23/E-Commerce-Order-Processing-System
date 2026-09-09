package com.commerceflow.orderservice.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.commerceflow.orderservice.entity.OrderStatus;
import com.commerceflow.orderservice.entity.OrderView;

/** Query-side persistence port: single-row, index-only reads over the CQRS projection. */
@Repository
public interface OrderViewRepository extends JpaRepository<OrderView, UUID> {

    Optional<OrderView> findByOrderNumber(String orderNumber);

    /**
     * The customer-facing order list.
     *
     * <p>{@code userId} is a mandatory filter rather than an optional one: a customer may only
     * ever page through their own orders. The back office uses {@link #searchAll} instead.
     */
    @Query("""
            SELECT v FROM OrderView v
             WHERE v.userId = :userId
               AND (:status IS NULL OR v.status = :status)
            """)
    Page<OrderView> searchForUser(@Param("userId") UUID userId,
                                  @Param("status") OrderStatus status,
                                  Pageable pageable);

    /** Back-office listing across every customer. */
    @Query("""
            SELECT v FROM OrderView v
             WHERE (:status IS NULL OR v.status = :status)
               AND (:userId IS NULL OR v.userId = :userId)
            """)
    Page<OrderView> searchAll(@Param("userId") UUID userId,
                              @Param("status") OrderStatus status,
                              Pageable pageable);

    long countByStatus(OrderStatus status);

    /**
     * Every projection belonging to a customer.
     *
     * <p>The read model holds its own copy of the customer's name and address, so erasure has to
     * scrub it too — nothing re-projects an old order on its own.
     */
    java.util.List<OrderView> findByUserId(java.util.UUID userId);
}
