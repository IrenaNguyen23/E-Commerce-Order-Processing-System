package com.commerceflow.orderservice.promotion;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, UUID> {

    Optional<Coupon> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * The back-office list.
     *
     * <p>Newest first, because a campaign somebody is looking for is nearly always one they set up
     * recently. Alphabetical by code would bury this week's work under three years of history.
     */
    Page<Coupon> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Coupon> findByActiveOrderByCreatedAtDesc(boolean active, Pageable pageable);

    /**
     * Takes one redemption from the campaign's allowance, if there is one left.
     *
     * <p><b>The whole point is that this is one statement.</b> Reading the count, comparing it and
     * writing it back is a race, and the race has a business consequence rather than a technical
     * one: a code meant for the first hundred customers is honoured a hundred and eleven times and
     * nothing anywhere reports an error. The database decides, under its own lock, and the number
     * of rows updated is the answer.
     *
     * @return 1 when a redemption was taken, 0 when the campaign is exhausted
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Coupon c SET c.redemptionCount = c.redemptionCount + 1, "
            + "c.updatedAt = CURRENT_TIMESTAMP WHERE c.id = :id "
            + "AND (c.maxRedemptions IS NULL OR c.redemptionCount < c.maxRedemptions)")
    int claim(@Param("id") UUID id);

    /**
     * Puts a redemption back after a cancellation.
     *
     * <p>Guarded with {@code > 0} so a double release cannot drive the counter negative, which
     * would silently hand out extra allowance on a campaign that was already full.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Coupon c SET c.redemptionCount = c.redemptionCount - 1, "
            + "c.updatedAt = CURRENT_TIMESTAMP WHERE c.id = :id AND c.redemptionCount > 0")
    int release(@Param("id") UUID id);
}
