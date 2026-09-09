package com.commerceflow.inventoryservice.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.inventoryservice.config.InventoryProperties;
import com.commerceflow.inventoryservice.entity.Product;
import com.commerceflow.inventoryservice.repository.ProductRepository;

/**
 * Reviews.
 *
 * <p>Most of what is pinned here is about moderation being hard to walk around, and about a
 * customer's email never reaching a product page.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewServiceTest {

    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @Mock
    private ReviewRepository reviews;

    @Mock
    private VerifiedPurchaseRepository purchases;

    @Mock
    private ProductRepository products;

    @Mock
    private AuditService auditService;

    private ReviewService service;
    private InventoryProperties properties;
    private AuthenticatedUser customer;
    private AuthenticatedUser admin;

    @BeforeEach
    void setUp() {
        properties = new InventoryProperties();
        service = new ReviewService(reviews, purchases, products, properties, auditService);

        customer = new AuthenticatedUser(UUID.randomUUID(), "ada@commerceflow.io",
                Set.of("CUSTOMER"), UUID.randomUUID().toString());
        admin = new AuthenticatedUser(UUID.randomUUID(), "admin@commerceflow.io",
                Set.of("ADMIN"), UUID.randomUUID().toString());

        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(Product.builder()
                .id(PRODUCT_ID).sku("CF-LAPTOP-001").name("Laptop").build()));
        when(reviews.save(any(Review.class))).thenAnswer(call -> call.getArgument(0));
        when(reviews.summarise(any())).thenReturn(new Object[] {"4.5", "2"});
    }

    private static ReviewRequest request(int rating, String authorName) {
        return new ReviewRequest(rating, "Good", "It works", authorName);
    }

    @Test
    @DisplayName("a new review waits for moderation")
    void newReviewIsHeld() {
        // A product page is somewhere anybody with an account can publish text under the shop's
        // name. Publishing on submission means the first abusive review is live until a human
        // happens to look.
        assertThat(service.submit(PRODUCT_ID, request(5, "Ada L."), customer).status())
                .isEqualTo(ReviewStatus.PENDING);
    }

    @Test
    @DisplayName("auto-publish is a switch, not a rewrite")
    void autoPublishCanBeTurnedOn() {
        properties.getReviews().setAutoPublish(true);

        assertThat(service.submit(PRODUCT_ID, request(5, "Ada L."), customer).status())
                .isEqualTo(ReviewStatus.PUBLISHED);
    }

    @Test
    @DisplayName("editing an approved review sends it back into the queue")
    void editingRequiresRemoderation() {
        Review approved = Review.builder()
                .id(UUID.randomUUID()).productId(PRODUCT_ID).userId(customer.userId())
                .rating(5).status(ReviewStatus.PUBLISHED).moderatedBy(admin.userId()).build();
        when(reviews.findByProductIdAndUserId(PRODUCT_ID, customer.userId()))
                .thenReturn(Optional.of(approved));

        ReviewResponse edited = service.submit(PRODUCT_ID, request(1, "Ada L."), customer);

        // Otherwise moderation is trivially defeated: submit something bland, wait for approval,
        // then edit it into whatever you actually meant to publish.
        assertThat(edited.status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(approved.getModeratedBy()).isNull();
    }

    @Test
    @DisplayName("submitting twice edits one review rather than adding a second")
    void oneReviewPerCustomer() {
        Review existing = Review.builder()
                .id(UUID.randomUUID()).productId(PRODUCT_ID).userId(customer.userId())
                .rating(5).status(ReviewStatus.PUBLISHED).build();
        when(reviews.findByProductIdAndUserId(PRODUCT_ID, customer.userId()))
                .thenReturn(Optional.of(existing));

        assertThat(service.submit(PRODUCT_ID, request(2, "Ada L."), customer).id())
                .isEqualTo(existing.getId());
    }

    @Test
    @DisplayName("a rating outside 1 to 5 is refused, not clamped")
    void ratingIsValidated() {
        // A 9 from a broken client is a bug worth surfacing. Silently storing 5 hides it behind
        // a suspiciously good rating.
        assertThatThrownBy(() -> service.submit(PRODUCT_ID, request(9, "Ada"), customer))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("between 1 and 5");
        verify(reviews, never()).save(any());
    }

    @Test
    @DisplayName("no author name publishes an initial, never an email address")
    void emailIsNeverPublished() {
        ReviewResponse review = service.submit(PRODUCT_ID, request(5, null), customer);

        // Writing a review is not consent to publish your email address on a product page.
        assertThat(review.authorName()).isEqualTo("A.");
        assertThat(review.authorName()).doesNotContain("@");
    }

    @Test
    @DisplayName("a verified purchase is recorded when the author has actually bought it")
    void verifiedPurchaseIsMarked() {
        when(purchases.existsByUserIdAndProductId(customer.userId(), PRODUCT_ID)).thenReturn(true);

        assertThat(service.submit(PRODUCT_ID, request(5, "Ada L."), customer).verifiedPurchase())
                .isTrue();
    }

    @Test
    @DisplayName("requiring a purchase refuses a reviewer who has not bought it")
    void purchaseCanBeRequired() {
        properties.getReviews().setRequirePurchase(true);
        when(purchases.existsByUserIdAndProductId(customer.userId(), PRODUCT_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.submit(PRODUCT_ID, request(5, "Ada"), customer))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("bought");
    }

    @Test
    @DisplayName("publishing recomputes the product's stored rating")
    void moderationRefreshesTheRating() {
        Review pending = Review.builder()
                .id(UUID.randomUUID()).productId(PRODUCT_ID).userId(customer.userId())
                .rating(4).status(ReviewStatus.PENDING).build();
        when(reviews.findById(pending.getId())).thenReturn(Optional.of(pending));

        service.moderate(pending.getId(), true, null, admin);

        // Recomputed fresh, not adjusted incrementally: an incremental average is correct until
        // one update is missed and then quietly wrong forever.
        verify(products).save(org.mockito.ArgumentMatchers.argThat(
                product -> product.getRatingCount() == 2));
    }

    @Test
    @DisplayName("a rejected review is kept, with its reason")
    void rejectionIsKept() {
        Review pending = Review.builder()
                .id(UUID.randomUUID()).productId(PRODUCT_ID).userId(customer.userId())
                .rating(1).status(ReviewStatus.PENDING).build();
        when(reviews.findById(pending.getId())).thenReturn(Optional.of(pending));

        ReviewResponse rejected = service.moderate(pending.getId(), false, "Abusive", admin);

        // So its author can be told why it is not showing, and so the decision is auditable
        // rather than a row that stopped existing.
        assertThat(rejected.status()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(rejected.moderationNote()).isEqualTo("Abusive");
        verify(reviews, never()).delete(any());
    }

    @Test
    @DisplayName("a customer cannot delete somebody else's review")
    void otherPeoplesReviewsAreInvisible() {
        Review someoneElses = Review.builder()
                .id(UUID.randomUUID()).productId(PRODUCT_ID).userId(UUID.randomUUID())
                .rating(5).status(ReviewStatus.PUBLISHED).build();
        when(reviews.findById(someoneElses.getId())).thenReturn(Optional.of(someoneElses));

        assertThatThrownBy(() -> service.delete(someoneElses.getId(), customer))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("an administrator can delete anyone's")
    void adminCanDeleteAny() {
        Review someoneElses = Review.builder()
                .id(UUID.randomUUID()).productId(PRODUCT_ID).userId(UUID.randomUUID())
                .rating(5).status(ReviewStatus.PUBLISHED).build();
        when(reviews.findById(someoneElses.getId())).thenReturn(Optional.of(someoneElses));

        service.delete(someoneElses.getId(), admin);

        verify(reviews).delete(someoneElses);
    }

    @Test
    @DisplayName("a product with no published reviews has a null average, not a zero")
    void unreviewedProductHasNoRating() {
        when(reviews.summarise(any())).thenReturn(new Object[] {null, "0"});
        Review pending = Review.builder()
                .id(UUID.randomUUID()).productId(PRODUCT_ID).userId(customer.userId())
                .rating(4).status(ReviewStatus.PENDING).build();
        when(reviews.findById(pending.getId())).thenReturn(Optional.of(pending));

        service.moderate(pending.getId(), false, "Spam", admin);

        // Zero would render as one star. "No reviews yet" and "reviewed, and terrible" are
        // different things and a customer can tell them apart.
        verify(products).save(org.mockito.ArgumentMatchers.argThat(
                product -> product.getRatingAverage() == null && product.getRatingCount() == 0));
    }
}
