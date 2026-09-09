package com.commerceflow.orderservice.promotion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.money.Money;
import com.commerceflow.orderservice.entity.Order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Applying, claiming and giving back discount codes.
 *
 * <h2>Two separate questions, deliberately kept apart</h2>
 *
 * <ul>
 *   <li>{@link #preview} — <em>what would this code be worth?</em> Read-only, used by the basket
 *       page, and it changes nothing.
 *   <li>{@link #apply} — <em>take one, and write it onto this order.</em> Claims from the
 *       campaign's allowance and records a redemption.
 * </ul>
 *
 * <p>Merging them would mean the basket page burning allowance every time somebody typed a code to
 * see what it did. Keeping them apart means a code can pass the preview and still fail at
 * checkout, when the last one has gone — which is honest, and is what the message says.
 *
 * <h2>The claim is the moment of truth</h2>
 *
 * <p>Everything before {@link CouponRepository#claim} is advisory. Validity windows, minimum
 * baskets and per-customer limits are all checked by reading, and any of them can be true at the
 * moment they are read and false a moment later. Only the conditional {@code UPDATE} is atomic,
 * and only its row count is trusted. That is what makes a limited campaign actually limited under
 * a hundred simultaneous checkouts.
 *
 * <h2>Cancelling gives the code back</h2>
 *
 * <p>{@link #release} runs when an order is cancelled. A customer whose payment was declined, or
 * who changed their mind, has not spent their coupon — and a code that silently stops working
 * after a failed checkout is indistinguishable, from the outside, from a code that never worked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository coupons;
    private final CouponRedemptionRepository redemptions;

    /**
     * What a code would be worth on this basket, without spending it.
     *
     * @throws BusinessException when the code cannot be used, with a message that says which
     *     reason — a customer told only "invalid code" cannot tell a typo from an expiry
     */
    @Transactional(readOnly = true)
    public CouponPreview preview(String rawCode, UUID userId, BigDecimal basketBeforeDiscount,
            BigDecimal shippingAmount, String currency) {

        Coupon coupon = require(rawCode);
        validate(coupon, userId, basketBeforeDiscount, currency);

        BigDecimal goodsOff = goodsDiscount(coupon, basketBeforeDiscount, currency);
        BigDecimal shippingOff = coupon.getType() == DiscountType.FREE_SHIPPING
                ? shippingAmount
                : Money.zero(currency);

        return new CouponPreview(coupon.getCode(), coupon.getType(), coupon.getDescription(),
                goodsOff, shippingOff, currency);
    }

    /**
     * Claims a code and writes its effect onto an order.
     *
     * <p>Called from inside the checkout transaction, before the order is priced: the discount
     * changes the taxable base and the free-delivery threshold, so it has to be settled first.
     *
     * @return what came off the goods, or {@code null} for a free-delivery coupon, which is
     *     applied by the pricing step instead
     */
    @Transactional
    public AppliedCoupon apply(String rawCode, Order order) {
        Coupon coupon = require(rawCode);
        BigDecimal basket = order.getItems().stream()
                .map(item -> item.getListPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        validate(coupon, order.getUserId(), basket, order.getCurrency());

        // Everything above was read. This is the only line that decides anything, and its row
        // count is the only answer trusted.
        if (coupons.claim(coupon.getId()) == 0) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "Code " + coupon.getCode() + " has been fully redeemed");
        }

        BigDecimal off = Money.zero(order.getCurrency());
        if (coupon.getType().reducesGoods()) {
            BigDecimal intended = goodsDiscount(coupon, basket, order.getCurrency());
            off = DiscountAllocator.allocate(order.getItems(), intended, order.getCurrency());
        }

        redemptions.save(CouponRedemption.builder()
                .id(UUID.randomUUID())
                .couponId(coupon.getId())
                .code(coupon.getCode())
                .userId(order.getUserId())
                .orderId(order.getId())
                .discountAmount(off)
                .currency(order.getCurrency())
                .redeemedAt(Instant.now())
                .build());

        log.info("Order {} redeemed {} ({}), {} off the goods",
                order.getOrderNumber(), coupon.getCode(), coupon.getType(), off);

        return new AppliedCoupon(coupon.getCode(), coupon.getType(), off);
    }

    /**
     * Returns a redemption to the campaign after a cancellation.
     *
     * <p>Quiet when there is nothing to release: an order with no coupon, or one already released,
     * is the ordinary case rather than a problem. Cancellation must not fail because of this.
     */
    @Transactional
    public void release(UUID orderId) {
        Optional<CouponRedemption> redemption = redemptions.findByOrderId(orderId);
        if (redemption.isEmpty()) {
            return;
        }
        CouponRedemption row = redemption.get();
        coupons.release(row.getCouponId());
        redemptions.delete(row);
        log.info("Order {} cancelled; returned code {} to its campaign", orderId, row.getCode());
    }

    /** Every use of a code, for the back office. */
    @Transactional(readOnly = true)
    public List<CouponRedemption> history(UUID couponId) {
        return redemptions.findAll().stream()
                .filter(r -> r.getCouponId().equals(couponId))
                .toList();
    }

    // =====================================================================================

    private Coupon require(String rawCode) {
        String code = Coupon.normalise(rawCode);
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Enter a discount code");
        }
        return coupons.findByCode(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNPROCESSABLE,
                        "We do not recognise the code " + code));
    }

    /**
     * Everything that can be known by reading.
     *
     * <p>Each refusal names its own reason. "Invalid code" for all of them would leave a customer
     * unable to tell a typo from a code that expired last night, and support unable to tell them.
     */
    private void validate(Coupon coupon, UUID userId, BigDecimal basket, String currency) {
        Instant now = Instant.now();

        if (!coupon.isLiveAt(now)) {
            String reason = !coupon.isActive() ? "is no longer available"
                    : coupon.getValidFrom() != null && now.isBefore(coupon.getValidFrom())
                            ? "cannot be used yet"
                            : "has expired";
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "Code " + coupon.getCode() + " " + reason);
        }

        if (!coupon.hasAllowanceLeft()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "Code " + coupon.getCode() + " has been fully redeemed");
        }

        if (coupon.getMinimumBasket() != null
                && basket.compareTo(coupon.getMinimumBasket()) < 0) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "Code " + coupon.getCode() + " needs a basket of at least "
                            + coupon.getMinimumBasket() + " " + currency);
        }

        // Not converted. "10 off" in a currency nobody is shopping in has no defensible value,
        // and inventing an exchange rate at checkout is worse than refusing the code.
        if (coupon.getType() == DiscountType.FIXED_AMOUNT
                && coupon.getCurrency() != null
                && !coupon.getCurrency().equalsIgnoreCase(currency)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "Code " + coupon.getCode() + " is only valid on orders in "
                            + coupon.getCurrency());
        }

        if (coupon.getPerCustomerLimit() != null && userId != null) {
            long used = redemptions.countByCouponIdAndUserId(coupon.getId(), userId);
            if (used >= coupon.getPerCustomerLimit()) {
                throw new BusinessException(ErrorCode.UNPROCESSABLE,
                        "You have already used code " + coupon.getCode());
            }
        }
    }

    /** What a code takes off the goods, before it is spread across the lines. */
    private static BigDecimal goodsDiscount(Coupon coupon, BigDecimal basket, String currency) {
        return switch (coupon.getType()) {
            case PERCENTAGE -> Money.percentageOf(basket, coupon.getValue(), currency);
            case FIXED_AMOUNT -> Money.round(coupon.getValue().min(basket), currency);
            case FREE_SHIPPING -> Money.zero(currency);
        };
    }
}
