package com.commerceflow.inventoryservice.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A sellable item in the catalogue. Stock levels live in {@link InventoryItem}. */
@Entity
@Table(name = "products", indexes = {
        @Index(name = "idx_products_category", columnList = "category_id"),
        @Index(name = "idx_products_active", columnList = "active")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "sku", nullable = false, unique = true, length = 64)
    private String sku;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    /**
     * Which section of the catalogue this is filed under.
     *
     * <p>An id rather than the category's name. A free-text category is four categories --
     * "Computers", "computers", "COMPUTERS" and a typo -- and nobody notices until a customer
     * filters by one and finds a third of the range missing.
     *
     * <p>Nullable, because a product can exist before anyone has decided where it belongs. It is
     * simply unfiled, which is visible, rather than being given a category it does not have.
     */
    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "price", nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "active", nullable = false)
    private boolean active;

    /**
     * Average of the published reviews, or {@code null} when there are none.
     *
     * <p>Denormalised deliberately. A listing page shows a star rating on every tile, and
     * averaging reviews at read time means one aggregate query per tile on the busiest page in the
     * shop. Recomputed whenever a review is published, rejected or removed — the rare operations.
     *
     * <p>Null rather than zero for an unreviewed product, because "no reviews yet" and "reviewed,
     * and terrible" are different things and a zero would render as the second.
     */
    @Column(name = "rating_average", precision = 3, scale = 2)
    private BigDecimal ratingAverage;

    /** How many published reviews the average is made of. */
    @Column(name = "rating_count", nullable = false)
    @Builder.Default
    private int ratingCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
