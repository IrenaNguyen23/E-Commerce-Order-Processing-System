package com.commerceflow.orderservice.pricing;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.money.Money;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * What delivery costs, and roughly when it arrives.
 *
 * <h2>The lookup order</h2>
 *
 * <ol>
 *   <li>The exact country and method.
 *   <li>The wildcard row ({@code *}) for the same method.
 *   <li>Nothing — and then the method is not offered, rather than being offered at a made-up
 *       price.
 * </ol>
 *
 * <p>Falling through to a wildcard rather than failing matters: a shop that cannot quote delivery
 * to a country is a shop that rejects the order at the last step of checkout, after the customer
 * has entered everything. A rate that is approximate and visible beats a checkout that dies.
 *
 * <h2>Free delivery is measured after discounts</h2>
 *
 * <p>The threshold compares against what the customer is actually paying for the goods. Comparing
 * against the pre-discount subtotal would give free delivery to a basket whose coupon took it below
 * the threshold — a rule customers find quickly and tell each other about.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShippingQuoteService {

    static final String WILDCARD = "*";

    private final ShippingRateRepository rates;

    /**
     * Prices one delivery method to one destination.
     *
     * @param goodsAfterDiscount what the customer is paying for the goods, which is what the
     *     free-delivery threshold is measured against
     * @throws BusinessException when the method cannot be offered to that country at all
     */
    @Transactional(readOnly = true)
    public ShippingQuote quote(String countryCode, ShippingMethod requested, int itemCount,
            BigDecimal goodsAfterDiscount, String currency) {

        ShippingMethod method = ShippingMethod.orDefault(requested);

        if (!method.isChargeable()) {
            // Collecting it yourself has no rate row by design, so that nobody can later edit
            // one and start charging people for walking to a shop.
            return new ShippingQuote(method, Money.zero(currency), currency, 0, 0, true,
                    "Collect in store");
        }

        ShippingRate rate = findRate(countryCode, method)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNPROCESSABLE,
                        method + " delivery is not available to " + countryCode));

        boolean free = rate.getFreeAbove() != null
                && goodsAfterDiscount.compareTo(rate.getFreeAbove()) >= 0;

        BigDecimal amount = free
                ? Money.zero(currency)
                : Money.round(rate.getBaseAmount()
                        .add(rate.getPerItemAmount().multiply(BigDecimal.valueOf(itemCount))),
                        currency);

        return new ShippingQuote(method, amount, currency, rate.getMinDays(), rate.getMaxDays(),
                free, describe(rate, free));
    }

    /**
     * Every method that can actually be offered to a destination, priced for this basket.
     *
     * <p>Used by the checkout form. Pickup is always in the list; a chargeable method appears only
     * if a rate exists for it, so the form never shows an option that would fail on submit.
     */
    @Transactional(readOnly = true)
    public List<ShippingQuote> options(String countryCode, int itemCount,
            BigDecimal goodsAfterDiscount, String currency) {

        List<ShippingQuote> priced = new java.util.ArrayList<>();
        for (ShippingMethod method : ShippingMethod.values()) {
            if (!method.isChargeable()) {
                priced.add(quote(countryCode, method, itemCount, goodsAfterDiscount, currency));
                continue;
            }
            findRate(countryCode, method).ifPresent(rate -> priced.add(
                    quote(countryCode, method, itemCount, goodsAfterDiscount, currency)));
        }
        priced.sort(Comparator.comparing(ShippingQuote::amount));
        return priced;
    }

    private Optional<ShippingRate> findRate(String countryCode, ShippingMethod method) {
        String iso = countryCode == null ? WILDCARD : countryCode;
        Optional<ShippingRate> exact =
                rates.findByCountryCodeAndMethodAndActiveTrue(iso, method);
        if (exact.isPresent()) {
            return exact;
        }
        Optional<ShippingRate> wildcard =
                rates.findByCountryCodeAndMethodAndActiveTrue(WILDCARD, method);
        if (wildcard.isPresent()) {
            log.debug("No {} rate for {}; using the rest-of-world rate", method, iso);
        }
        return wildcard;
    }

    private static String describe(ShippingRate rate, boolean free) {
        String window = rate.getMinDays() == rate.getMaxDays()
                ? rate.getMinDays() + " working days"
                : rate.getMinDays() + "–" + rate.getMaxDays() + " working days";
        return free ? "Free delivery, " + window : window;
    }
}
