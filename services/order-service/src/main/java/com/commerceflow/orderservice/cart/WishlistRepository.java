package com.commerceflow.orderservice.cart;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WishlistRepository extends JpaRepository<WishlistItem, UUID> {

    List<WishlistItem> findByUserIdOrderByAddedAtDesc(UUID userId);

    Optional<WishlistItem> findByUserIdAndProductId(UUID userId, UUID productId);

    boolean existsByUserIdAndProductId(UUID userId, UUID productId);

    long countByUserId(UUID userId);
}
