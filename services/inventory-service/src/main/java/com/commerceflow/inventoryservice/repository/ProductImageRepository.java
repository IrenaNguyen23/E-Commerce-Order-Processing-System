package com.commerceflow.inventoryservice.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.commerceflow.inventoryservice.entity.ProductImage;

@Repository
public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {

    /**
     * A product's gallery in display order.
     *
     * <p>Created-at is the tiebreak, so two images that were somehow given the same position come
     * back in a stable order rather than shuffling between requests.
     */
    List<ProductImage> findByProductIdOrderByPositionAscCreatedAtAsc(UUID productId);

    Optional<ProductImage> findFirstByProductIdOrderByPositionAscCreatedAtAsc(UUID productId);

    /** Scoped by product, so an id guessed from one product cannot be fetched under another. */
    Optional<ProductImage> findByIdAndProductId(UUID id, UUID productId);

    /**
     * The galleries of several products at once.
     *
     * <p>One query for a whole page of search results. The bytes are lazy, so this fetches
     * metadata only — the alternative, a primary-image lookup per product, would be fifty round
     * trips to decorate fifty tiles.
     */
    List<ProductImage> findByProductIdInOrderByPositionAscCreatedAtAsc(List<UUID> productIds);

    long countByProductId(UUID productId);

    void deleteByProductId(UUID productId);
}
