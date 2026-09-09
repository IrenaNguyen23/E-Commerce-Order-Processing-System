package com.commerceflow.orderservice.cart;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    Optional<Cart> findByUserId(UUID userId);

    /**
     * Baskets nobody has touched for a while.
     *
     * <p>Used by the sweeper. Note what a stale cart is <em>not</em>: it holds no stock and blocks
     * nothing, so this is housekeeping rather than a correctness problem — which is why the
     * threshold is months rather than hours.
     */
    @Query("SELECT c FROM Cart c WHERE c.updatedAt < :before")
    List<Cart> findAbandonedBefore(@Param("before") Instant before);
}
