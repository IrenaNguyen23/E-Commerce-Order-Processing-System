package com.commerceflow.inventoryservice.review;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What a customer thought of a product.
 *
 * <h2>The author's name is copied, not looked up</h2>
 *
 * <p>{@link #authorName} is a snapshot, for the same reason an order line copies a product name:
 * a review is a historical statement. It also avoids a join into a different service on every
 * product page — Inventory has no access to the accounts database and should not acquire one to
 * render "Ada L.".
 *
 * <h2>Verified purchase is decided once, at submission</h2>
 *
 * <p>Not recomputed on read. Whether somebody had bought the thing when they reviewed it is a fact
 * about the moment they wrote it; a review that silently becomes "verified" months later, because
 * the author eventually bought one, is a badge that means nothing.
 */
@Entity
@Table(name = "product_reviews", indexes = {
        @Index(name = "idx_reviews_product", columnList = "product_id, status, created_at"),
        @Index(name = "idx_reviews_author", columnList = "user_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Review {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** How the author is shown. Copied at submission; see the class comment. */
    @Column(name = "author_name", nullable = false, length = 100)
    private String authorName;

    /** One to five. Anything else is refused rather than clamped. */
    @Column(name = "rating", nullable = false)
    private int rating;

    @Column(name = "title", length = 150)
    private String title;

    @Column(name = "body", length = 4000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private ReviewStatus status = ReviewStatus.PENDING;

    /**
     * Whether this customer had actually bought the product when they wrote this.
     *
     * <p>Established from {@code order.completed}, which Inventory subscribes to. Not asked of
     * Order Service at read time: that would put a synchronous call to another service in the
     * middle of rendering a product page.
     */
    @Column(name = "verified_purchase", nullable = false)
    @Builder.Default
    private boolean verifiedPurchase = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "moderated_by")
    private UUID moderatedBy;

    @Column(name = "moderated_at")
    private Instant moderatedAt;

    /** Why a review was rejected, shown to its author and to nobody else. */
    @Column(name = "moderation_note", length = 255)
    private String moderationNote;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public boolean isPublished() {
        return status == ReviewStatus.PUBLISHED;
    }
}
