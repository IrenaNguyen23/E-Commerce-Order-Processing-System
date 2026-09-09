package com.commerceflow.orderservice.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.orderservice.entity.Order;
import com.commerceflow.orderservice.entity.OrderItem;
import com.commerceflow.orderservice.entity.OrderStatus;

/**
 * Discount codes.
 *
 * <p>Two things here are worth more than the rest. The first is that a limited campaign is limited
 * by <em>one conditional update</em> and nothing else — every other check is advisory and can be
 * true when read and false a moment later. The second is that a fixed-amount discount has to be
 * spread across the lines without losing or inventing a cent, which is arithmetic that quietly
 * goes wrong rather than failing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponServiceTest {

    @Mock
    private CouponRepository coupons;

    @Mock
    private CouponRedemptionRepository redemptions;

    private CouponService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        service = new CouponService(coupons, redemptions);
        userId = UUID.randomUUID();
        when(coupons.claim(any())).thenReturn(1);
        when(redemptions.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    // =====================================================================================
    // Fixtures
    // =====================================================================================

    private Coupon given(Coupon coupon) {
        when(coupons.findByCode(coupon.getCode())).thenReturn(Optional.of(coupon));
        return coupon;
    }

    private static Coupon.CouponBuilder base(String code, DiscountType type, String value) {
        return Coupon.builder()
                .id(UUID.randomUUID()).code(code).type(type).value(new BigDecimal(value))
                .active(true).redemptionCount(0);
    }

    private static Order order(String currency, OrderItem... items) {
        Order order = Order.builder()
                .id(UUID.randomUUID()).orderNumber("CF-20260828-000001")
                .userId(UUID.randomUUID()).userEmail("ada@commerceflow.io")
                .status(OrderStatus.CREATED).currency(currency)
                .shippingAddress("Keizersgracht 1").shippingCountry("NL")
                .items(new ArrayList<>()).subtotalAmount(BigDecimal.ZERO)
                .discountTotal(BigDecimal.ZERO).totalAmount(BigDecimal.ZERO).build();
        for (OrderItem item : items) {
            order.addItem(item);
        }
        return order;
    }

    private static OrderItem line(String listPrice, int qty) {
        OrderItem item = OrderItem.builder()
                .id(UUID.randomUUID()).productId(UUID.randomUUID()).sku("CF-1")
                .productName("Thing").quantity(qty).listPrice(new BigDecimal(listPrice))
                .discountAmount(BigDecimal.ZERO).build();
        item.recalculateSubtotal();
        return item;
    }

    private static BigDecimal takenOff(Order order) {
        return order.getItems().stream()
                .map(item -> item.getDiscountAmount().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // =====================================================================================

    @Nested
    @DisplayName("Claiming")
    class Claiming {

        @Test
        @DisplayName("an exhausted campaign is refused by the database, not by a read")
        void exhaustedCampaignIsRefusedByTheClaim() {
            // The read says there is one left. The conditional update says otherwise, because
            // somebody else took it in between. Only the update is trusted.
            given(base("LAST1", DiscountType.PERCENTAGE, "0.1000")
                    .maxRedemptions(100).redemptionCount(99).build());
            when(coupons.claim(any())).thenReturn(0);

            assertThatThrownBy(() -> service.apply("LAST1", order("EUR", line("100.00", 1))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("fully redeemed");

            // And nothing was recorded, so the ledger cannot disagree with the counter.
            verify(redemptions, never()).save(any());
        }

        @Test
        @DisplayName("a successful claim records a redemption")
        void claimIsRecorded() {
            given(base("WELCOME10", DiscountType.PERCENTAGE, "0.1000").build());
            Order order = order("EUR", line("100.00", 1));

            service.apply("welcome10", order);

            verify(coupons).claim(any());
            verify(redemptions).save(any(CouponRedemption.class));
        }

        @Test
        @DisplayName("codes are matched case-insensitively")
        void codeIsCaseInsensitive() {
            given(base("WELCOME10", DiscountType.PERCENTAGE, "0.1000").build());

            // "welcome10" and "WELCOME10" are the same code to everybody except a database, and
            // a customer typing what was printed on a poster should not have to guess.
            assertThat(service.apply(" welcome10 ", order("EUR", line("100.00", 1))).code())
                    .isEqualTo("WELCOME10");
        }

        @Test
        @DisplayName("a previewed code does not spend anything")
        void previewClaimsNothing() {
            given(base("WELCOME10", DiscountType.PERCENTAGE, "0.1000").build());

            service.preview("WELCOME10", userId, new BigDecimal("100.00"), BigDecimal.ZERO, "EUR");

            // Otherwise a basket page burns a limited campaign on curiosity alone.
            verify(coupons, never()).claim(any());
            verify(redemptions, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Refusals, each with its own reason")
    class Refusals {

        @Test
        @DisplayName("an expired code says it expired")
        void expiredCodeSaysSo() {
            given(base("SUMMER24", DiscountType.PERCENTAGE, "0.2000")
                    .validUntil(Instant.now().minus(1, ChronoUnit.DAYS)).build());

            // Not "invalid code". A customer told that cannot tell a typo from an expiry, and
            // neither can the person they ring about it.
            assertThatThrownBy(() -> service.preview("SUMMER24", userId,
                    new BigDecimal("100.00"), BigDecimal.ZERO, "EUR"))
                    .hasMessageContaining("has expired");
        }

        @Test
        @DisplayName("a code that has not started says that instead")
        void futureCodeSaysSo() {
            given(base("BLACKFRIDAY", DiscountType.PERCENTAGE, "0.3000")
                    .validFrom(Instant.now().plus(7, ChronoUnit.DAYS)).build());

            assertThatThrownBy(() -> service.preview("BLACKFRIDAY", userId,
                    new BigDecimal("100.00"), BigDecimal.ZERO, "EUR"))
                    .hasMessageContaining("cannot be used yet");
        }

        @Test
        @DisplayName("a basket under the minimum says what the minimum is")
        void minimumBasketSaysTheNumber() {
            given(base("TENOFF", DiscountType.FIXED_AMOUNT, "10.00")
                    .currency("EUR").minimumBasket(new BigDecimal("100.00")).build());

            assertThatThrownBy(() -> service.preview("TENOFF", userId,
                    new BigDecimal("50.00"), BigDecimal.ZERO, "EUR"))
                    .hasMessageContaining("at least 100");
        }

        @Test
        @DisplayName("a customer who already used it is told so")
        void perCustomerLimitIsEnforced() {
            Coupon coupon = given(base("WELCOME10", DiscountType.PERCENTAGE, "0.1000")
                    .perCustomerLimit(1).build());
            when(redemptions.countByCouponIdAndUserId(coupon.getId(), userId)).thenReturn(1L);

            assertThatThrownBy(() -> service.preview("WELCOME10", userId,
                    new BigDecimal("100.00"), BigDecimal.ZERO, "EUR"))
                    .hasMessageContaining("already used");
        }

        @Test
        @DisplayName("a fixed-amount code in another currency is refused, not converted")
        void currencyMismatchIsRefused() {
            given(base("TENOFF", DiscountType.FIXED_AMOUNT, "10.00").currency("EUR").build());

            // "10 off" in a currency nobody is shopping in has no defensible value, and
            // inventing an exchange rate at checkout is worse than refusing the code.
            assertThatThrownBy(() -> service.preview("TENOFF", userId,
                    new BigDecimal("100.00"), BigDecimal.ZERO, "USD"))
                    .hasMessageContaining("only valid on orders in EUR");
        }

        @Test
        @DisplayName("an unknown code is refused without pretending to know it")
        void unknownCodeIsRefused() {
            when(coupons.findByCode("NOPE")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.preview("nope", userId,
                    new BigDecimal("100.00"), BigDecimal.ZERO, "EUR"))
                    .hasMessageContaining("do not recognise");
        }
    }

    @Nested
    @DisplayName("Spreading a fixed amount across lines")
    class Allocation {

        @Test
        @DisplayName("the parts add up to exactly the whole")
        void allocationLosesNothing() {
            given(base("TENOFF", DiscountType.FIXED_AMOUNT, "10.00").currency("EUR").build());

            // Three equal lines. Ten divided three ways is 3.333..., and rounding each share
            // independently gives 9.99 -- the customer is short-changed by a cent on a coupon
            // that said ten, and nothing reports it.
            Order order = order("EUR", line("10.00", 1), line("10.00", 1), line("10.00", 1));

            service.apply("TENOFF", order);

            assertThat(takenOff(order)).isEqualByComparingTo(new BigDecimal("10.00"));
        }

        @Test
        @DisplayName("shares are proportional to what each line costs")
        void allocationIsProportional() {
            given(base("TENOFF", DiscountType.FIXED_AMOUNT, "10.00").currency("EUR").build());
            Order order = order("EUR", line("75.00", 1), line("25.00", 1));

            service.apply("TENOFF", order);

            // 75% and 25% of the basket, so 7.50 and 2.50.
            assertThat(order.getItems().get(0).getDiscountAmount())
                    .isEqualByComparingTo(new BigDecimal("7.50"));
            assertThat(order.getItems().get(1).getDiscountAmount())
                    .isEqualByComparingTo(new BigDecimal("2.50"));
        }

        @Test
        @DisplayName("a discount larger than the basket takes it to zero, not below")
        void discountCannotPayTheCustomer() {
            given(base("HUGE", DiscountType.FIXED_AMOUNT, "500.00").currency("EUR").build());
            Order order = order("EUR", line("20.00", 1));

            service.apply("HUGE", order);
            order.recalculateTotals();

            // An order with a negative total is one the payment step has to refuse anyway --
            // after the customer has been shown it.
            assertThat(order.getItems().get(0).getSubtotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(takenOff(order)).isEqualByComparingTo(new BigDecimal("20.00"));
        }

        @Test
        @DisplayName("a per-unit discount never gives back more than the line's share")
        void perUnitRoundingNeverOverGives() {
            given(base("TENOFF", DiscountType.FIXED_AMOUNT, "10.00").currency("EUR").build());

            // One line of three units. 10 / 3 = 3.33 per unit, x3 = 9.99. The missing cent is
            // not invented back: rounding the per-unit figure up would hand out 10.02 for a
            // coupon worth 10, which is the failure that gets shared on the internet.
            Order order = order("EUR", line("50.00", 3));

            service.apply("TENOFF", order);

            assertThat(order.getItems().get(0).getDiscountAmount())
                    .isEqualByComparingTo(new BigDecimal("3.33"));
            assertThat(takenOff(order)).isLessThanOrEqualTo(new BigDecimal("10.00"));
        }

        @Test
        @DisplayName("a percentage applies to each line at its own value")
        void percentageIsPerLine() {
            given(base("WELCOME10", DiscountType.PERCENTAGE, "0.1000").build());
            Order order = order("EUR", line("100.00", 1), line("50.00", 2));

            service.apply("WELCOME10", order);

            // 10% of a 200.00 basket.
            assertThat(takenOff(order)).isEqualByComparingTo(new BigDecimal("20.00"));
        }
    }

    @Nested
    @DisplayName("Free delivery")
    class FreeShipping {

        @Test
        @DisplayName("does not touch the goods, and therefore does not touch the tax")
        void freeShippingLeavesGoodsAlone() {
            given(base("FREESHIP", DiscountType.FREE_SHIPPING, "0").build());
            Order order = order("EUR", line("100.00", 1));

            AppliedCoupon applied = service.apply("FREESHIP", order);

            // A free-delivery code that reduced the goods would quietly reduce the tax with
            // them, and the invoice would show tax that does not match its own rate.
            assertThat(applied.freesShipping()).isTrue();
            assertThat(takenOff(order)).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("is previewed as a delivery saving, separately from any goods saving")
        void previewSeparatesTheTwoSavings() {
            given(base("FREESHIP", DiscountType.FREE_SHIPPING, "0").build());

            CouponPreview preview = service.preview("FREESHIP", userId,
                    new BigDecimal("100.00"), new BigDecimal("4.95"), "EUR");

            assertThat(preview.goodsDiscount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(preview.shippingDiscount()).isEqualByComparingTo(new BigDecimal("4.95"));
            assertThat(preview.totalSaving()).isEqualByComparingTo(new BigDecimal("4.95"));
        }
    }

    @Nested
    @DisplayName("Giving it back")
    class Release {

        @Test
        @DisplayName("cancelling an order returns the redemption to the campaign")
        void cancellationReleasesTheCode() {
            UUID orderId = UUID.randomUUID();
            UUID couponId = UUID.randomUUID();
            CouponRedemption row = CouponRedemption.builder()
                    .id(UUID.randomUUID()).couponId(couponId).code("WELCOME10")
                    .userId(userId).orderId(orderId).discountAmount(new BigDecimal("10.00"))
                    .currency("EUR").redeemedAt(Instant.now()).build();
            when(redemptions.findByOrderId(orderId)).thenReturn(Optional.of(row));

            service.release(orderId);

            // A declined card is not a spent coupon. Keeping the redemption would make the code
            // stop working for a customer who did nothing wrong, indistinguishably from a code
            // that never worked at all.
            verify(coupons).release(couponId);
            verify(redemptions).delete(row);
        }

        @Test
        @DisplayName("an order with no coupon releases nothing and does not complain")
        void releasingWithoutACouponIsQuiet() {
            UUID orderId = UUID.randomUUID();
            when(redemptions.findByOrderId(orderId)).thenReturn(Optional.empty());

            service.release(orderId);

            // Cancellation must not fail because there was nothing to give back -- this runs on
            // every cancelled order, and most of them never had a code.
            verify(coupons, never()).release(any());
        }

        @Test
        @DisplayName("releasing twice is harmless")
        void doubleReleaseIsSafe() {
            UUID orderId = UUID.randomUUID();
            when(redemptions.findByOrderId(orderId)).thenReturn(Optional.empty());

            service.release(orderId);
            service.release(orderId);

            verify(coupons, never()).release(any());
        }
    }

    @Test
    @DisplayName("what the order records is what was attributed, not the coupon's face value")
    void orderRecordsWhatWasActuallyTakenOff() {
        given(base("HUGE", DiscountType.FIXED_AMOUNT, "500.00").currency("EUR").build());
        Order order = order("EUR", line("20.00", 1));

        AppliedCoupon applied = service.apply("HUGE", order);

        // The coupon says 500; 20 came off. An order has to be able to explain its own
        // arithmetic without anything else being consulted.
        assertThat(applied.goodsDiscount()).isEqualByComparingTo(new BigDecimal("20.00"));
    }

    @Test
    @DisplayName("a blank code is a prompt, not an error about codes")
    void blankCodeAsksForOne() {
        assertThatThrownBy(() -> service.preview("  ", userId,
                new BigDecimal("100.00"), BigDecimal.ZERO, "EUR"))
                .hasMessageContaining("Enter a discount code");
    }
}
