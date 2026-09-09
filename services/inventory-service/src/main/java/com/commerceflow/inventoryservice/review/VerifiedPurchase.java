package com.commerceflow.inventoryservice.review;

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
 * A record that somebody actually bought a product.
 *
 * <h2>Why this table exists at all</h2>
 *
 * <p>Marking a review "verified purchase" needs an answer to "has this customer bought this
 * product", and that fact lives in Order Service. There were three ways to get it:
 *
 * <ul>
 *   <li><b>Ask Order Service when a review is submitted.</b> A synchronous call between two
 *       services that otherwise do not depend on each other in that direction — Order depends on
 *       Inventory, not the reverse — and one that makes review submission fail when Order is down.
 *   <li><b>Ask Order Service when a product page is rendered.</b> Worse: a call per page view.
 *   <li><b>Listen.</b> Order Service already announces {@code order.completed}. Subscribing costs
 *       Order Service nothing, adds no coupling it can see, and leaves this a local lookup.
 * </ul>
 *
 * <p>The third is what this is. It is also the clearest demonstration of why the domain events
 * survived the move to orchestration: the saga issues commands to named participants, but
 * {@code order.completed} is an announcement, and a new consumer of it required no change to the
 * flow at all.
 *
 * <h2>What it is not</h2>
 *
 * <p>Not a purchase history. It records that a pair happened, not how many times or for how much —
 * anything more would be duplicating Order Service's data, which is the failure mode this pattern
 * usually falls into.
 */
@Entity
@Table(name = "verified_purchases", indexes = {
        @Index(name = "idx_verified_purchases", columnList = "user_id, product_id", unique = true)
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifiedPurchase {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /** When the order that established this completed. */
    @Column(name = "purchased_at", nullable = false)
    private Instant purchasedAt;
}
