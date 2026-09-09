package com.commerceflow.inventoryservice.review;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    Page<Review> findByProductIdAndStatus(UUID productId, ReviewStatus status, Pageable pageable);

    Page<Review> findByStatus(ReviewStatus status, Pageable pageable);

    Optional<Review> findByProductIdAndUserId(UUID productId, UUID userId);

    List<Review> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * The rating of one product, recomputed from its published reviews.
     *
     * <p>Called when a review is published, rejected or deleted — never on a read path. A product
     * listing that averaged reviews on the fly would run one aggregate per tile; the answer is
     * denormalised onto the product for exactly that reason.
     */
    @Query("""
            SELECT COALESCE(AVG(r.rating), 0), COUNT(r)
              FROM Review r
             WHERE r.productId = :productId AND r.status = 'PUBLISHED'
            """)
    Object[] summarise(@Param("productId") UUID productId);

    long countByProductIdAndStatus(UUID productId, ReviewStatus status);
}
