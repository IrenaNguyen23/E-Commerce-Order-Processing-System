package com.commerceflow.orderservice.returns;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, UUID> {

    List<ReturnRequest> findByOrderIdOrderByRequestedAtDesc(UUID orderId);

    List<ReturnRequest> findByUserIdOrderByRequestedAtDesc(UUID userId);

    /** The operator queue: everything in one state, oldest first, which is the order to work it. */
    Page<ReturnRequest> findByStatusOrderByRequestedAtAsc(ReturnStatus status, Pageable pageable);

    Page<ReturnRequest> findAllByOrderByRequestedAtDesc(Pageable pageable);

    /**
     * How much of each line has already been claimed on this order.
     *
     * <p>Counting everything except the requests that came to nothing. A rejected or cancelled
     * request returned no goods, so its quantities must not block a later honest one — but an
     * open request must, or a customer could ask for the same item twice and be refunded twice.
     *
     * <p>Returned as {@code orderItemId -> quantity} so the caller can check every line in one
     * query rather than one per line.
     */
    @Query("""
            SELECT i.orderItemId, SUM(i.quantity)
              FROM ReturnRequestItem i
             WHERE i.returnRequest.orderId = :orderId
               AND i.returnRequest.status NOT IN (
                   com.commerceflow.orderservice.returns.ReturnStatus.REJECTED,
                   com.commerceflow.orderservice.returns.ReturnStatus.CANCELLED)
             GROUP BY i.orderItemId
            """)
    List<Object[]> claimedQuantitiesRaw(@Param("orderId") UUID orderId);

    /** {@link #claimedQuantitiesRaw} as the map the caller actually wants. */
    default Map<UUID, Integer> claimedQuantities(UUID orderId) {
        return claimedQuantitiesRaw(orderId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> (UUID) row[0],
                        row -> ((Number) row[1]).intValue()));
    }
}
