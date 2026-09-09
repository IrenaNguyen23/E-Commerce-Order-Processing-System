package com.commerceflow.orderservice.cart;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What a customer has put aside but not yet bought.
 *
 * <h2>A cart stores no prices, and that is the whole design</h2>
 *
 * <p>An order snapshots everything — name, price, tax rate, the picture the customer saw — because
 * an order is a record of what was agreed. A cart is the opposite: it is a list of intentions, and
 * it has to show what things cost <em>now</em>.
 *
 * <p>Storing a price here would mean a basket saved on Tuesday quoting Tuesday's price on Friday,
 * and then the checkout charging Friday's. The customer would have watched a number change between
 * one screen and the next with no explanation. So a cart line is a product id and a quantity;
 * every price on the basket page is read live, and the first time anything is frozen is when the
 * order is placed.
 *
 * <h2>One cart per customer</h2>
 *
 * <p>Keyed by user id rather than by a cart id, so signing in on a second device finds the same
 * basket rather than starting a new one. That is the entire point of moving the cart off the
 * device.
 */
@Entity
@Table(name = "carts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Cart {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Unique. The customer is the identity of the cart. */
    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @Builder.Default
    private List<CartItem> items = new ArrayList<>();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Optional<CartItem> find(UUID productId) {
        return items.stream().filter(item -> item.getProductId().equals(productId)).findFirst();
    }

    /**
     * Sets the quantity of a product, adding the line if it is not there.
     *
     * <p>Sets rather than adds, because that is what a quantity control on a basket page does. An
     * "add" endpoint that a client calls twice — a double tap, a retry — would otherwise leave the
     * customer with four of something they asked for twice.
     */
    public CartItem put(UUID productId, int quantity) {
        CartItem existing = find(productId).orElse(null);
        if (existing != null) {
            existing.setQuantity(quantity);
            existing.setUpdatedAt(Instant.now());
            return existing;
        }

        CartItem item = CartItem.builder()
                .id(UUID.randomUUID())
                .cart(this)
                .productId(productId)
                .quantity(quantity)
                .addedAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        items.add(item);
        return item;
    }

    public boolean remove(UUID productId) {
        return items.removeIf(item -> item.getProductId().equals(productId));
    }

    public int totalQuantity() {
        return items.stream().mapToInt(CartItem::getQuantity).sum();
    }
}
