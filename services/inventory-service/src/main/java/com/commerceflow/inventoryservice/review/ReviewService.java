package com.commerceflow.inventoryservice.review;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.dto.PageResponse;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.inventoryservice.config.InventoryProperties;
import com.commerceflow.inventoryservice.entity.Product;
import com.commerceflow.inventoryservice.repository.ProductRepository;
import com.commerceflow.inventoryservice.service.ProductService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Product reviews, and the rating they add up to.
 *
 * <h2>One review per customer per product</h2>
 *
 * <p>Enforced by a unique constraint, not only by a check here. Somebody submitting twice is
 * editing their review, and that is what the second submission does — the alternative is a product
 * page where one determined person is most of the opinions.
 *
 * <h2>The rating is stored on the product, not computed on read</h2>
 *
 * <p>A listing page shows a star rating on every tile. Averaging reviews at read time means one
 * aggregate query per tile, or a join whose cost grows with every review ever written, on the page
 * that gets the most traffic in the shop.
 *
 * <p>So {@code Product.ratingAverage} and {@code ratingCount} are denormalised and recomputed
 * whenever a review is published, rejected or removed — the rare operations. The recomputation is
 * a fresh {@code AVG} over the published reviews rather than an incremental adjustment, because an
 * incrementally-maintained average drifts the first time an event is missed and nothing ever
 * notices.
 *
 * <h2>Editing sends a review back for moderation</h2>
 *
 * <p>Otherwise the moderation queue is trivially defeated: submit something innocuous, wait for it
 * to be approved, then edit it into whatever you actually wanted to publish.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviews;
    private final VerifiedPurchaseRepository purchases;
    private final ProductRepository products;
    private final InventoryProperties properties;
    private final AuditService auditService;

    /** Published reviews for a product, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<ReviewResponse> forProduct(UUID productId, int page, int size) {
        Page<Review> found = reviews.findByProductIdAndStatus(productId, ReviewStatus.PUBLISHED,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));

        return PageResponse.of(found.getContent().stream().map(ReviewService::toResponse).toList(),
                page, size, found.getTotalElements());
    }

    /** Everything the caller has written, whatever its state — including their rejections. */
    @Transactional(readOnly = true)
    public List<ReviewResponse> mine(UUID userId) {
        return reviews.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(ReviewService::toResponse)
                .toList();
    }

    /**
     * Writes or replaces the caller's review of a product.
     *
     * @throws BusinessException when the rating is out of range, or the deployment requires a
     *     purchase and the caller has not made one
     */
    @Transactional
    @CacheEvict(cacheNames = {ProductService.CACHE_BY_ID, ProductService.CACHE_BY_SKU},
            allEntries = true)
    public ReviewResponse submit(UUID productId, ReviewRequest request, AuthenticatedUser author) {
        Product product = products.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + productId));

        if (request.rating() < 1 || request.rating() > 5) {
            // Refused rather than clamped. A 9 submitted by a broken client is a bug worth
            // surfacing, and silently storing 5 would hide it behind a suspiciously good rating.
            throw new BusinessException(ErrorCode.BAD_REQUEST, "A rating is between 1 and 5");
        }

        boolean verified = purchases.existsByUserIdAndProductId(author.userId(), productId);
        if (properties.getReviews().isRequirePurchase() && !verified) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "Only customers who have bought " + product.getName() + " can review it");
        }

        Review review = reviews.findByProductIdAndUserId(productId, author.userId())
                .orElseGet(() -> Review.builder()
                        .id(UUID.randomUUID())
                        .productId(productId)
                        .userId(author.userId())
                        .createdAt(Instant.now())
                        .build());

        review.setAuthorName(displayName(request.authorName(), author));
        review.setRating(request.rating());
        review.setTitle(trimToNull(request.title()));
        review.setBody(trimToNull(request.body()));
        review.setVerifiedPurchase(verified);
        review.setUpdatedAt(Instant.now());

        // An edit goes back into the queue. Otherwise moderation is defeated by submitting
        // something bland, waiting for approval, and then editing it.
        review.setStatus(properties.getReviews().isAutoPublish()
                ? ReviewStatus.PUBLISHED
                : ReviewStatus.PENDING);
        review.setModeratedBy(null);
        review.setModeratedAt(null);
        review.setModerationNote(null);

        Review saved = reviews.save(review);
        refreshRating(productId);

        log.info("Review of {} by {} saved as {}", product.getSku(), author.userId(),
                saved.getStatus());
        return toResponse(saved);
    }

    /** The moderation queue. */
    @Transactional(readOnly = true)
    public PageResponse<ReviewResponse> pending(int page, int size) {
        Page<Review> found = reviews.findByStatus(ReviewStatus.PENDING,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt")));

        return PageResponse.of(found.getContent().stream().map(ReviewService::toResponse).toList(),
                page, size, found.getTotalElements());
    }

    /**
     * A moderator's decision.
     *
     * <p>A rejected review is kept, not deleted: its author can be told why it is not showing, and
     * the decision remains auditable rather than becoming a row that stopped existing.
     */
    @Transactional
    @CacheEvict(cacheNames = {ProductService.CACHE_BY_ID, ProductService.CACHE_BY_SKU},
            allEntries = true)
    public ReviewResponse moderate(UUID reviewId, boolean publish, String note,
            AuthenticatedUser moderator) {

        Review review = reviews.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Review not found: " + reviewId));

        review.setStatus(publish ? ReviewStatus.PUBLISHED : ReviewStatus.REJECTED);
        review.setModeratedBy(moderator.userId());
        review.setModeratedAt(Instant.now());
        review.setModerationNote(trimToNull(note));
        review.setUpdatedAt(Instant.now());

        Review saved = reviews.save(review);
        refreshRating(review.getProductId());

        auditService.record(moderator, publish ? "REVIEW_PUBLISHED" : "REVIEW_REJECTED",
                "REVIEW", reviewId,
                (publish ? "Published" : "Rejected") + " a review of product "
                        + review.getProductId());

        log.info("Review {} {} by {}", reviewId, publish ? "published" : "rejected",
                moderator.userId());
        return toResponse(saved);
    }

    /**
     * Removes a review.
     *
     * <p>A customer may delete their own; an administrator may delete anyone's. A customer
     * deleting somebody else's gets not-found rather than forbidden, for the same reason as
     * everywhere else: "this exists but is not yours" is still an answer about it.
     */
    @Transactional
    @CacheEvict(cacheNames = {ProductService.CACHE_BY_ID, ProductService.CACHE_BY_SKU},
            allEntries = true)
    public void delete(UUID reviewId, AuthenticatedUser caller) {
        Review review = reviews.findById(reviewId)
                .filter(candidate -> caller.isAdmin()
                        || candidate.getUserId().equals(caller.userId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Review not found: " + reviewId));

        UUID productId = review.getProductId();
        reviews.delete(review);
        reviews.flush();
        refreshRating(productId);
    }

    /**
     * Recomputes a product's rating from its published reviews.
     *
     * <p>A fresh average rather than an incremental adjustment. An incrementally maintained
     * average is correct until one event is missed, and then it is quietly wrong forever with
     * nothing to compare it against.
     */
    private void refreshRating(UUID productId) {
        Object[] summary = reviews.summarise(productId);

        // Spring Data hands a single-row aggregate back nested one level deep. Guarded rather
        // than assumed, because the shape differs between providers and an unguarded cast here
        // fails at runtime on a path that is only exercised when somebody writes a review.
        Object[] row = summary != null && summary.length == 1 && summary[0] instanceof Object[] inner
                ? inner
                : summary;

        BigDecimal average = BigDecimal.ZERO;
        long count = 0;
        if (row != null && row.length == 2) {
            average = row[0] == null
                    ? BigDecimal.ZERO
                    : new BigDecimal(row[0].toString()).setScale(2, RoundingMode.HALF_UP);
            count = row[1] == null ? 0 : Long.parseLong(row[1].toString());
        }

        final BigDecimal finalAverage = average;
        final long finalCount = count;
        products.findById(productId).ifPresent(product -> {
            product.setRatingAverage(finalCount == 0 ? null : finalAverage);
            product.setRatingCount((int) finalCount);
            products.save(product);
        });
    }

    /**
     * What to show as the author.
     *
     * <p>Falls back to the local part of the email with everything after the first character
     * replaced — "ada@commerceflow.io" becomes "Ada L." only if they gave a name, otherwise
     * "A." — because publishing somebody's email address on a product page is a disclosure
     * nobody consented to by writing a review.
     */
    private static String displayName(String requested, AuthenticatedUser author) {
        String trimmed = trimToNull(requested);
        if (trimmed != null) {
            return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
        }
        String email = author.email();
        if (email == null || email.isBlank()) {
            return "A customer";
        }
        String local = email.split("@")[0];
        return local.isEmpty()
                ? "A customer"
                : Character.toUpperCase(local.charAt(0)) + ".";
    }

    static ReviewResponse toResponse(Review review) {
        return new ReviewResponse(review.getId(), review.getProductId(), review.getAuthorName(),
                review.getRating(), review.getTitle(), review.getBody(), review.getStatus(),
                review.isVerifiedPurchase(), review.getModerationNote(), review.getCreatedAt(),
                review.getUpdatedAt());
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
