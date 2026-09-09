package com.commerceflow.orderservice.entity;

import java.math.BigDecimal;
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
 * One ordered line.
 *
 * <p>Name and unit price are copied from the catalogue at order time on purpose: an order is a
 * historical record, and a later price change must not rewrite what the customer agreed to pay.
 */
@Entity
@Table(name = "order_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    /** Category name at order time, for display. The catalogue may have reorganised since. */
    @Column(name = "product_category", length = 100)
    private String productCategory;

    /**
     * The category's slug at order time, and what the tax rate was looked up by.
     *
     * <p>Stored next to the name rather than instead of it, because they answer different
     * questions. An invoice has to print a name a person recognises; a tax calculation has to
     * match something that does not change when a merchandiser renames a section.
     */
    @Column(name = "product_category_slug", length = 100)
    private String productCategorySlug;

    /** The image the customer saw. Products get re-shot and replaced; this one does not. */
    @Column(name = "product_image_url", length = 500)
    private String productImageUrl;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /**
     * Catalogue price at order time, before any discount.
     *
     * <p>Separate from {@link #unitPrice} so that a reduction is recorded rather than implied.
     * With no promotion engine yet the two are always equal — but the day one exists, an old
     * order still explains itself instead of showing a price nobody can account for.
     */
    @Column(name = "list_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal listPrice;

    /** Per unit reduction. Structurally zero until a promotion engine exists. */
    @Column(name = "discount_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal discountAmount;

    /** What was actually charged per unit: {@code listPrice - discountAmount}. */
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "subtotal", nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotal;

    /**
     * What the tax was called where this went: "VAT", "BTW", "GST".
     *
     * <p>Snapshotted with everything else, because an invoice has to name the tax it charged and
     * the name is a property of the jurisdiction at the time, not of the product.
     */
    @Column(name = "tax_name", length = 50)
    private String taxName;

    /** The rate applied, as a fraction: 0.2100 is 21%. Frozen, like the price. */
    @Column(name = "tax_rate", precision = 6, scale = 4)
    private BigDecimal taxRate;

    /**
     * Tax on this line, already rounded.
     *
     * <p>Rounded per line and then summed by the order — never derived by apportioning a rounded
     * order total backwards, which is how a cent goes missing. A customer adding the lines up by
     * hand gets the total printed on the invoice.
     */
    @Column(name = "tax_amount", precision = 19, scale = 4)
    private BigDecimal taxAmount;

    /** Records the tax charged on this line. */
    public void applyTax(String name, BigDecimal rate, BigDecimal amount) {
        this.taxName = name;
        this.taxRate = rate;
        this.taxAmount = amount;
    }

    /**
     * Marks this line as attracting no tax.
     *
     * <p>Explicitly cleared rather than left as whatever was there before: a line carrying a stale
     * rate from an earlier calculation would be an invoice that names a tax it did not charge.
     */
    public void clearTax() {
        this.taxName = null;
        this.taxRate = null;
        this.taxAmount = null;
    }

    /** Tax on this line, or zero — so callers adding up do not have to think about nulls. */
    public BigDecimal taxOrZero() {
        return taxAmount == null ? BigDecimal.ZERO : taxAmount;
    }

    /**
     * Derives the charged price and the line total from the snapshot.
     *
     * <p>Holds the invariant the whole snapshot rests on:
     *
     * <pre>
     *   unitPrice = listPrice - discountAmount
     *   subtotal  = unitPrice * quantity
     * </pre>
     *
     * <p>Computed here rather than trusted from a caller, because a line whose numbers do not add
     * up is an invoice that cannot be defended — and nothing else in the system would notice.
     */
    public void recalculateSubtotal() {
        if (listPrice == null) {
            // Set before this runs on the normal path; this keeps a partially built line from
            // exploding rather than silently pricing itself at zero.
            this.listPrice = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        }
        if (discountAmount == null) {
            this.discountAmount = BigDecimal.ZERO;
        }

        this.unitPrice = listPrice.subtract(discountAmount).max(BigDecimal.ZERO);
        this.subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
