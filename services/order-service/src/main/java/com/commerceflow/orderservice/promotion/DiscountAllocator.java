package com.commerceflow.orderservice.promotion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.commerceflow.common.money.Money;
import com.commerceflow.orderservice.entity.OrderItem;

/**
 * Spreads one discount across the lines it applies to.
 *
 * <h2>Why an order-level discount cannot stay at order level</h2>
 *
 * <p>Tax is charged per line, at that line's own rate. A "10 off" sitting on the order has no rate
 * — until it is attributed to particular goods, there is no answer to how much tax it removes. So
 * a fixed-amount coupon is pushed down onto the lines in proportion to what they cost, and from
 * then on the whole system only ever deals with per-line discounts.
 *
 * <h2>The cent that goes missing</h2>
 *
 * <p>Proportional shares almost never divide evenly. Ten off a basket of three equal lines is
 * 3.333… each; rounded independently that is 3.33 three times, and the customer is given 9.99 for
 * a coupon that said 10. Rounding up instead gives 10.02, which is worse — the shop is out of
 * pocket and the invoice still does not add up.
 *
 * <p>So the shares are rounded down and the remainder is handed out one minor unit at a time, to
 * the lines with the largest fractional part first. The parts then sum to exactly the whole, by
 * construction rather than by luck, and the line that gets the extra cent is the line that was
 * closest to earning it.
 *
 * <h2>The discount is per unit, because that is where it is stored</h2>
 *
 * <p>{@link OrderItem#getDiscountAmount()} is a per-unit figure. A line's allocation therefore has
 * to divide by its quantity — and that division has the same remainder problem, which is why a
 * line's share is capped so that {@code discountAmount x quantity} never exceeds what was
 * allocated to it. A coupon that appears to give back more than its face value is a bug that gets
 * shared on the internet.
 */
final class DiscountAllocator {

    private DiscountAllocator() {
    }

    /**
     * Attributes {@code total} across {@code lines}, in proportion to what each line costs.
     *
     * <p>Writes a per-unit {@code discountAmount} onto each line and re-derives its subtotal. The
     * amount actually attributed is returned, which can be less than asked for when the discount
     * is larger than the basket — a coupon cannot pay the customer.
     */
    static BigDecimal allocate(List<OrderItem> lines, BigDecimal total, String currency) {
        BigDecimal basket = lines.stream()
                .map(item -> item.getListPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (basket.signum() <= 0 || total.signum() <= 0) {
            return Money.zero(currency);
        }

        // A discount larger than the basket takes it to zero and no further. The alternative is
        // an order with a negative total, which the payment step would have to refuse anyway —
        // after the customer had been shown it.
        BigDecimal capped = total.min(basket);

        int scale = Money.scaleOf(currency);
        BigDecimal minorUnit = BigDecimal.ONE.movePointLeft(scale);

        List<Share> shares = new ArrayList<>();
        BigDecimal handedOut = BigDecimal.ZERO;

        for (OrderItem item : lines) {
            BigDecimal lineValue = item.getListPrice()
                    .multiply(BigDecimal.valueOf(item.getQuantity()));
            BigDecimal exact = capped.multiply(lineValue)
                    .divide(basket, scale + 4, RoundingMode.HALF_UP);

            // Down, deliberately. Rounding each share to nearest can overshoot the total, and
            // giving away more than the coupon is worth is the failure that gets noticed.
            BigDecimal floor = exact.setScale(scale, RoundingMode.DOWN);
            shares.add(new Share(item, floor, exact.subtract(floor)));
            handedOut = handedOut.add(floor);
        }

        // Whatever the flooring left over, one minor unit at a time, largest fraction first.
        BigDecimal remainder = capped.subtract(handedOut);
        shares.sort(Comparator.comparing(Share::fraction).reversed());
        int index = 0;
        while (remainder.compareTo(minorUnit) >= 0 && index < shares.size()) {
            shares.get(index).add(minorUnit);
            remainder = remainder.subtract(minorUnit);
            index++;
            if (index == shares.size() && remainder.compareTo(minorUnit) >= 0) {
                // More remainder than lines: go round again rather than dropping it.
                index = 0;
            }
        }

        BigDecimal attributed = BigDecimal.ZERO;
        for (Share share : shares) {
            attributed = attributed.add(share.applyToLine(currency));
        }
        return attributed;
    }

    /** One line's slice, and how close it came to earning the next minor unit. */
    private static final class Share {

        private final OrderItem item;
        private BigDecimal amount;
        private final BigDecimal fraction;

        Share(OrderItem item, BigDecimal amount, BigDecimal fraction) {
            this.item = item;
            this.amount = amount;
            this.fraction = fraction;
        }

        BigDecimal fraction() {
            return fraction;
        }

        void add(BigDecimal minorUnit) {
            this.amount = amount.add(minorUnit);
        }

        /**
         * Writes the share onto the line as a per-unit figure.
         *
         * @return what was actually taken off this line, which is the per-unit figure times the
         *     quantity — not the share, because dividing by the quantity rounds again
         */
        BigDecimal applyToLine(String currency) {
            int scale = Money.scaleOf(currency);

            // DOWN so that per-unit x quantity never exceeds the share. Anything else lets a
            // coupon hand back more than its face value.
            BigDecimal perUnit = amount
                    .divide(BigDecimal.valueOf(item.getQuantity()), scale, RoundingMode.DOWN)
                    .min(item.getListPrice());

            item.setDiscountAmount(perUnit);
            item.recalculateSubtotal();
            return perUnit.multiply(BigDecimal.valueOf(item.getQuantity()));
        }
    }
}
