package com.commerceflow.inventoryservice.review;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VerifiedPurchaseRepository extends JpaRepository<VerifiedPurchase, UUID> {

    boolean existsByUserIdAndProductId(UUID userId, UUID productId);

    /**
     * Removes everything recorded about what one customer bought.
     *
     * <p>These rows exist only to decide whether somebody may claim a verified-purchase badge.
     * Nothing depends on them after the fact, so an erasure removes them outright rather than
     * anonymising — an anonymous record of what somebody bought serves no purpose at all.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true,
            flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "DELETE FROM VerifiedPurchase p WHERE p.userId = :userId")
    int deleteByUserId(@org.springframework.data.repository.query.Param("userId") UUID userId);
}
