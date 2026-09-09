package com.commerceflow.inventoryservice.review;

/**
 * Where a review is in moderation.
 *
 * <h2>Why new reviews are not published immediately</h2>
 *
 * <p>A product page is a place anybody with an account can publish arbitrary text under a shop's
 * name. Publishing on submission means the first abusive or fraudulent review is live until
 * somebody notices; holding them means the worst case is a delay.
 *
 * <p>That trade-off is a policy, not a law of nature — {@code commerceflow.inventory.reviews
 * .auto-publish} flips it for a deployment that would rather have immediacy and moderate
 * afterwards. The default is the cautious one because the cost of being wrong is asymmetric.
 */
public enum ReviewStatus {

    /** Written, not yet visible to anyone but its author and an administrator. */
    PENDING,

    /** Visible on the product page and counted in its rating. */
    PUBLISHED,

    /**
     * Refused. Still visible to its author, with the reason.
     *
     * <p>Kept rather than deleted so that a customer who asks why their review vanished can be
     * told, and so that a moderator's decision is auditable rather than a row that stopped
     * existing.
     */
    REJECTED;

    /** Only published reviews count towards a product's rating. */
    public boolean countsTowardsRating() {
        return this == PUBLISHED;
    }
}
