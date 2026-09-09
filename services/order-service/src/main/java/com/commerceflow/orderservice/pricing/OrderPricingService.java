package com.commerceflow.orderservice.pricing;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.money.Money;
import com.commerceflow.orderservice.entity.Order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns a basket of lines into the single number a customer is asked to pay.
 *
 * <h2>Order of operations, and why it is not arbitrary</h2>
 *
 * <ol>
 *   <li><b>Discounts</b> are already on the lines when this runs — they change the taxable base,
 *       so they must be settled first. Taxing a list price and then discounting would charge tax
 *       on money nobody paid.
 *   <li><b>Delivery</b> is quoted next, because the free-delivery threshold is measured against
 *       what the customer actually pays for the goods.
 *   <li><b>Tax</b> is applied per line, against the discounted line total.
 *   <li><b>The total</b> is assembled from all four by the aggregate.
 * </ol>
 *
 * <p>Getting this sequence wrong does not produce an error. It produces an invoice that is out by
 * a small amount, on some baskets and not others.
 *
 * <h2>Pricing happens once, at checkout</h2>
 *
 * <p>Nothing re-prices an existing order. The rates that applied are copied onto it, so a rate
 * change tomorrow moves what the next customer pays and nothing that has already been agreed —
 * the same rule as the product snapshot, for the same reason.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPricingService {

    private final TaxCalculator taxCalculator;
    private final ShippingQuoteService shipping;

    /**
     * Prices an order in place: delivery, then tax, then the totals.
     *
     * @param method what the customer chose; {@code null} means standard delivery
     * @return the delivery quote that was applied, so the caller can tell the customer when it
     *     will arrive and what it cost
     */
    @Transactional(readOnly = true)
    public ShippingQuote price(Order order, ShippingMethod method) {
        return price(order, method, false);
    }

    /**
     * Prices an order, optionally with delivery waived by a coupon.
     *
     * <p>Free delivery is applied <b>after</b> the rate is worked out, not by skipping the quote.
     * The order then records what delivery would have cost and that it was waived, which is what
     * lets anyone answer later whether a campaign was expensive.
     */
    @Transactional(readOnly = true)
    public ShippingQuote price(Order order, ShippingMethod method, boolean deliveryIsFree) {
        String currency = order.getCurrency();

        // Discounts are already on the lines, so this is what the customer pays for the goods —
        // and therefore what the free-delivery threshold has to be measured against.
        BigDecimal goods = order.goodsAfterDiscount();

        ShippingQuote quote = shipping.quote(order.getShippingCountry(), method,
                order.itemCount(), goods, currency);
        if (deliveryIsFree && quote.amount().signum() > 0) {
            quote = new ShippingQuote(quote.method(), Money.zero(currency), currency,
                    quote.minDays(), quote.maxDays(), true,
                    quote.description() + ", free with your code");
        }
        order.setShippingMethod(quote.method());
        order.setShippingAmount(quote.amount());
        order.setDeliveryMinDays(quote.minDays());
        order.setDeliveryMaxDays(quote.maxDays());

        taxCalculator.applyTo(order, order.getShippingCountry());
        order.recalculateTotals();

        // Rounded last, once, so what is stored is expressible in the currency it is stored in.
        // Every component was already rounded; this catches a currency with no minor unit, where
        // an unrounded sum of rounded parts can still carry a fraction the gateway cannot charge.
        order.setTotalAmount(Money.round(order.getTotalAmount(), currency));

        log.debug("Priced order {}: goods {}, tax {}, delivery {} = {} {}",
                order.getOrderNumber(), goods, order.getTaxTotal(), order.getShippingAmount(),
                order.getTotalAmount(), currency);

        return quote;
    }
}
