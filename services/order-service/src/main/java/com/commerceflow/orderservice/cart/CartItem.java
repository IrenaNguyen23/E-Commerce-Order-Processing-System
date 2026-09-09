package com.commerceflow.orderservice.cart;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One line of a basket: a product and how many of it.
 *
 * <p>No price, no name, no picture. Everything a basket page displays is read live from the
 * catalogue, so a product that is repriced, renamed or re-photographed while sitting in somebody's
 * basket shows its current state — which is the correct behaviour for a basket and exactly the
 * wrong behaviour for an order.
 *
 * <p>{@link #addedAt} is kept separate from {@link #updatedAt} because they answer different
 * questions: how long something has been sitting in a basket, versus when the customer last
 * changed their mind about how many.
 */
@Entity
@Table(name = "cart_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
