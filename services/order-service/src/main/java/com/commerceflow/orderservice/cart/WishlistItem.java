package com.commerceflow.orderservice.cart;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Something a customer wants to remember, without committing to buying it.
 *
 * <p>Its own table rather than a flag on {@link CartItem}, because the two behave differently in
 * the one way that matters: a basket is emptied when an order is placed and a wishlist is not.
 * Modelling "saved for later" as a cart line with a boolean means every query that touches the
 * basket has to remember to exclude them, and the first one that forgets charges somebody for
 * something they were only thinking about.
 *
 * <p>Like a cart line, it stores no price. What a wished-for item costs is whatever it costs when
 * the customer looks — a wishlist that quoted last month's price would be a promise nobody made.
 */
@Entity
@Table(name = "wishlist_items", indexes = {
        @Index(name = "idx_wishlist_user", columnList = "user_id, added_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WishlistItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;
}
