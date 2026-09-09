package com.commerceflow.orderservice.pricing;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.money.Money;
import com.commerceflow.orderservice.entity.Order;
import com.commerceflow.orderservice.entity.OrderItem;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Works out the tax on an order and writes it onto the lines.
 *
 * <h2>Per line, then added up. Never the other way round.</h2>
 *
 * <p>This is the whole reason the class exists. It is tempting to tax the order total once — one
 * multiplication, one rounding — and it is wrong as soon as a basket mixes rates, which in most of
 * Europe means as soon as somebody buys a book and a kettle together. Worse, it is wrong by
 * amounts small enough that nobody notices until an accountant reconciles a quarter.
 *
 * <p>So: each line is taxed at its own rate, each result is rounded to something the currency can
 * express, and the rounded figures are summed. The order's tax total is then by construction the
 * sum of the numbers printed on the invoice — a customer adding up the lines by hand gets the
 * total on the page.
 *
 * <h2>The rate is snapshotted, like everything else on an order</h2>
 *
 * <p>Each line keeps the rate that was applied and the name it was called. When a government
 * changes VAT next April, this order still shows what was charged and why. Nothing re-prices a
 * past order, and no report has to reconstruct historic rates from a table that has moved on.
 *
 * <h2>What is deliberately not taxed here</h2>
 *
 * <p><b>Delivery.</b> In several jurisdictions a delivery charge is taxed at the rate of the goods
 * it carries, which for a mixed-rate basket means apportioning one charge across two or three
 * rates. That is a genuine accounting question with a jurisdiction-specific answer, and a
 * half-correct implementation of it is worse than a stated boundary: the shipping charge quoted by
 * {@link ShippingQuoteService} is treated as tax-inclusive, and the rate card is priced on that
 * basis. Anyone selling into a jurisdiction where that is not acceptable has to model it properly,
 * and will find this comment when they go looking.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxCalculator {

    private final TaxRateRepository taxRates;

    /**
     * Applies tax to every line of an order and returns the total.
     *
     * <p>Mutates the lines: each gets its rate, the name of the tax and the amount. The order's
     * own totals are recalculated by the caller, so that all of an order's money is frozen in one
     * place.
     *
     * @param countryCode where the goods are going; a country with no rates means no tax, which is
     *     a real and visible answer rather than a default
     */
    @Transactional(readOnly = true)
    public BigDecimal applyTo(Order order, String countryCode) {
        String iso = countryCode == null ? null : countryCode.trim().toUpperCase(Locale.ROOT);
        Map<String, TaxRate> byCategory = ratesFor(iso);

        if (byCategory.isEmpty()) {
            // Not an error. Plenty of destinations attract no tax from this seller, and inventing
            // one because a table was empty would be worse than charging none.
            log.debug("No tax rates configured for {}; charging none", iso);
            order.getItems().forEach(OrderItem::clearTax);
            return Money.zero(order.getCurrency());
        }

        BigDecimal total = Money.zero(order.getCurrency());
        for (OrderItem item : order.getItems()) {
            // The slug, not the display name. A renamed section must not change the tax
            // charged on what is inside it.
            TaxRate rate = pick(byCategory, item.getProductCategorySlug());
            if (rate == null) {
                item.clearTax();
                continue;
            }

            // The taxable base is what the customer actually pays for the line — after the
            // discount, not before. Taxing the list price would charge tax on money nobody paid.
            BigDecimal base = item.getSubtotal();
            BigDecimal tax = Money.percentageOf(base, rate.getRate(), order.getCurrency());

            item.applyTax(rate.getName(), rate.getRate(), tax);
            total = total.add(tax);
        }
        return total;
    }

    /**
     * The rates for a country, keyed by category, with the standard rate under {@code null}.
     *
     * <p>A single query for the whole basket. Looking a rate up per line would turn a twenty-line
     * order into twenty round trips against a table with a few dozen rows in it.
     */
    private Map<String, TaxRate> ratesFor(String countryCode) {
        if (countryCode == null) {
            return Map.of();
        }
        List<TaxRate> rows = taxRates.findByCountryCode(countryCode);
        Map<String, TaxRate> byCategory = new HashMap<>();
        for (TaxRate row : rows) {
            byCategory.put(key(row.getCategory()), row);
        }
        return byCategory;
    }

    /** Most specific first: the category's own rate, then the country's standard rate. */
    private static TaxRate pick(Map<String, TaxRate> byCategory, String category) {
        TaxRate specific = byCategory.get(key(category));
        return specific != null ? specific : byCategory.get(key(null));
    }

    /**
     * Rate rows are keyed by category slug, matched case-insensitively.
     *
     * <p>Case-insensitive as a courtesy to whoever types the rate card by hand; slugs are already
     * lower case by construction, so this only ever forgives an operator.
     */
    private static String key(String category) {
        return category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
    }
}
