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
import java.util.Optional;
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
import com.commerceflow.common.exception.ConflictException;

/**
 * Setting up campaigns.
 *
 * <p>Two of these are about a mistake the API refuses to guess its way out of, and two are about
 * an edit that would land as a database error if it were not caught here. None of them is a
 * validation rule for its own sake.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponAdminServiceTest {

    @Mock
    private CouponRepository coupons;

    @Mock
    private CouponRedemptionRepository redemptions;

    @Mock
    private AuditService auditService;

    private CouponAdminService service;
    private Coupon existing;

    @BeforeEach
    void setUp() {
        service = new CouponAdminService(coupons, redemptions, auditService);

        existing = Coupon.builder()
                .id(UUID.randomUUID()).code("WELCOME10").type(DiscountType.PERCENTAGE)
                .value(new BigDecimal("0.1000")).active(true)
                .maxRedemptions(100).redemptionCount(40)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();

        when(coupons.save(any(Coupon.class))).thenAnswer(call -> call.getArgument(0));
        when(coupons.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(redemptions.countByCouponId(any())).thenReturn(0L);
    }

    private static CouponRequest request(String code, DiscountType type, String value) {
        return new CouponRequest(code, "A campaign", type, new BigDecimal(value),
                type == DiscountType.FIXED_AMOUNT ? "EUR" : null,
                null, null, null, null, null, true);
    }

    @Test
    @DisplayName("a percentage of 1 or more is refused rather than interpreted")
    void percentageMustBeAFraction() {
        // Somebody typed 10 meaning ten per cent. Quietly treating it as 1000% would create a code
        // that gives everything away and then some, and it would look like it worked.
        assertThatThrownBy(() ->
                service.create(request("BIG", DiscountType.PERCENTAGE, "10")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("0.10 is 10%");
        verify(coupons, never()).save(any());
    }

    @Test
    @DisplayName("a fixed amount without a currency is refused")
    void fixedAmountNeedsACurrency() {
        CouponRequest noCurrency = new CouponRequest("TENOFF", null, DiscountType.FIXED_AMOUNT,
                new BigDecimal("10.00"), null, null, null, null, null, null, true);

        // Without one there is no way to tell whether "10 off" means euros or dong, and the claim
        // path has nothing to compare the order's currency against.
        assertThatThrownBy(() -> service.create(noCurrency))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("needs a currency");
    }

    @Test
    @DisplayName("a duplicate code is refused case-insensitively")
    void duplicateCodeIsRefused() {
        when(coupons.existsByCode("WELCOME10")).thenReturn(true);

        assertThatThrownBy(() ->
                service.create(request("welcome10", DiscountType.PERCENTAGE, "0.10")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a campaign that would end before it starts is refused")
    void windowMustMakeSense() {
        Instant now = Instant.now();
        CouponRequest backwards = new CouponRequest("SUMMER", null, DiscountType.PERCENTAGE,
                new BigDecimal("0.20"), null, null, null, null,
                now, now.minus(1, ChronoUnit.DAYS), true);

        assertThatThrownBy(() -> service.create(backwards))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("end before it started");
    }

    @Test
    @DisplayName("the code itself cannot be changed")
    void codeIsImmutable() {
        // It is printed on posters and recorded as text on every order that used it. Renaming it
        // makes all of that refer to something that no longer exists — and nobody would notice
        // until a customer rang about a code that worked yesterday.
        assertThatThrownBy(() -> service.update(existing.getId(),
                request("WELCOME20", DiscountType.PERCENTAGE, "0.10")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be changed");
    }

    @Test
    @DisplayName("the cap cannot be lowered below what has already gone out")
    void capCannotGoBelowTheCount() {
        CouponRequest lowered = new CouponRequest("WELCOME10", null, DiscountType.PERCENTAGE,
                new BigDecimal("0.10"), null, null, 10, null, null, null, true);

        // The database has a check constraint for exactly this. Catching it here turns a 500 with
        // a constraint name in it into a sentence naming the actual number.
        assertThatThrownBy(() -> service.update(existing.getId(), lowered))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already been used 40 times");
    }

    @Test
    @DisplayName("setting the cap to exactly what has gone out is allowed, and stops it")
    void capCanBeSetToTheCurrentCount() {
        CouponRequest stopped = new CouponRequest("WELCOME10", null, DiscountType.PERCENTAGE,
                new BigDecimal("0.10"), null, null, 40, null, null, null, true);

        // The documented way to stop a campaign without deactivating it. The conditional claim
        // then refuses every further redemption.
        CouponResponse updated = service.update(existing.getId(), stopped);

        assertThat(updated.remaining()).isZero();
        assertThat(updated.live()).isFalse();
    }

    @Test
    @DisplayName("the value and the window are editable on a live campaign")
    void aLiveCampaignCanBeAdjusted() {
        CouponRequest better = new CouponRequest("WELCOME10", "Now 15%", DiscountType.PERCENTAGE,
                new BigDecimal("0.15"), null, null, 200, null, null, null, true);

        // A campaign doing better or worse than expected gets adjusted. Refusing that sends
        // operators to the database, which is worse in every way.
        assertThat(service.update(existing.getId(), better).value())
                .isEqualByComparingTo(new BigDecimal("0.15"));
    }

    @Test
    @DisplayName("a used code is not deleted")
    void usedCodeIsNotDeleted() {
        when(redemptions.countByCouponId(existing.getId())).thenReturn(40L);

        assertThatThrownBy(() -> service.delete(existing.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Switch it off instead");
        verify(coupons, never()).delete(any());
    }

    @Test
    @DisplayName("an unused code is deleted")
    void unusedCodeIsDeleted() {
        service.delete(existing.getId());

        verify(coupons).delete(existing);
    }

    @Test
    @DisplayName("currency is not stored on a code where it would mean nothing")
    void currencyOnlyAppliesToFixedAmounts() {
        CouponRequest percentageWithCurrency = new CouponRequest("PCT", null,
                DiscountType.PERCENTAGE, new BigDecimal("0.10"), "EUR",
                null, null, null, null, null, true);

        // A percentage is a percentage in any currency. Storing one invites a later check that
        // refuses a perfectly good code on an order in another currency.
        assertThat(service.create(percentageWithCurrency).currency()).isNull();
    }

    @Test
    @DisplayName("live is computed, not stored")
    void liveIsComputed() {
        Coupon expired = Coupon.builder()
                .id(UUID.randomUUID()).code("SUMMER24").type(DiscountType.PERCENTAGE)
                .value(new BigDecimal("0.20")).active(true).redemptionCount(0)
                .validUntil(Instant.now().minus(1, ChronoUnit.DAYS))
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        when(coupons.findById(expired.getId())).thenReturn(Optional.of(expired));

        // A campaign expires by the clock, not by a sweep. A stored flag would be right only as
        // often as something remembered to run.
        assertThat(service.get(expired.getId()).live()).isFalse();
        assertThat(service.get(expired.getId()).active()).isTrue();
    }
}
