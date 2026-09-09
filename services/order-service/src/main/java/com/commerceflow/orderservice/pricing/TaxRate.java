package com.commerceflow.orderservice.pricing;

import java.math.BigDecimal;
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
 * How much tax a category of goods attracts in one country.
 *
 * <h2>A table, not a constant</h2>
 *
 * <p>Rates change by government decision, at short notice, on a date somebody else picks. A rate
 * compiled into the application means a deployment on the day a budget takes effect; a rate in a
 * table means an operator changing a number. Anything a legislature can change is data.
 *
 * <h2>Category rates, and why "no category" is a row too</h2>
 *
 * <p>Reduced rates on books, food and children's clothing are the norm rather than the exception in
 * most of Europe, so a single rate per country would be wrong in every one of those countries. A
 * row with a {@code null} category is the country's standard rate and is what a category with no
 * specific rate falls back to.
 *
 * <p>Lookup is therefore <b>most specific first</b>: the exact category, then the country default.
 * A country with no rows at all means "we do not charge tax there", which is a deliberate,
 * visible state — the alternative, defaulting to some rate, invents a tax nobody set.
 *
 * <h2>No effective dates, on purpose</h2>
 *
 * <p>There is no {@code valid_from} column, and history is safe anyway: an order copies the rate
 * and the amount onto its own lines at checkout. Changing this table alters what the <em>next</em>
 * order is charged and nothing that has already happened, which is the same guarantee the product
 * snapshot gives. Temporal rate rows would only matter for re-pricing a past order, which is not
 * something the system does or should do.
 */
@Entity
@Table(name = "tax_rates", indexes = {
        @Index(name = "idx_tax_rates_lookup", columnList = "country_code, category")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxRate {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** ISO-3166 alpha-2, upper case. */
    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    /** {@code null} means "the standard rate for this country". */
    @Column(name = "category", length = 100)
    private String category;

    /** What the customer sees this called: "VAT", "BTW", "GST", "Sales tax". */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** A fraction, not a percentage: 0.2100 is 21%. */
    @Column(name = "rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal rate;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** True when this is the country's fallback rather than a category rate. */
    public boolean isCountryDefault() {
        return category == null;
    }
}
