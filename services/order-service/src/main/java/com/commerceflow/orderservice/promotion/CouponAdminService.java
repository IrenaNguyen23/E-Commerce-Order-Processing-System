package com.commerceflow.orderservice.promotion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.dto.PageResponse;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Setting up discount codes.
 *
 * <p>Kept apart from {@link CouponService}, which is the customer-facing half — previewing and
 * claiming. The two have almost nothing in common: one is a merchandiser configuring a campaign,
 * the other is a checkout under contention, and the only thing they share is the table.
 *
 * <h2>Three things this refuses, and why each would otherwise be a real problem</h2>
 *
 * <ol>
 *   <li><b>Changing a code.</b> It is printed on posters, quoted in emails, and stored as text on
 *       every order and redemption that used it. Renaming it makes all of that refer to something
 *       that no longer exists — and unlike a category name, nobody would notice until a customer
 *       rang up about a code that had worked yesterday.
 *   <li><b>Lowering the cap below what has already been redeemed.</b> The database has a check
 *       constraint saying the count cannot exceed the cap, so this would arrive as a constraint
 *       violation and a 500. Refused here with a message that says the number.
 *   <li><b>Deleting a code that has been used.</b> The redemption ledger points at it, and those
 *       rows are the record of what a campaign cost. Deactivating is what somebody actually wants
 *       and it stops the code working immediately.
 * </ol>
 *
 * <h2>What is deliberately editable, including on a live campaign</h2>
 *
 * <p>The value, the window, the minimum basket and the caps. A campaign that is doing better or
 * worse than expected gets adjusted, and refusing that would send operators to the database.
 * Changes affect the <em>next</em> order and nothing already placed, because an order snapshotted
 * what came off it — the same rule as everywhere else.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponAdminService {

    private final CouponRepository coupons;
    private final CouponRedemptionRepository redemptions;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<CouponResponse> list(Boolean active, int page, int size) {
        Page<Coupon> found = active == null
                ? coupons.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
                : coupons.findByActiveOrderByCreatedAtDesc(active, PageRequest.of(page, size));

        return PageResponse.of(found.getContent().stream().map(CouponResponse::of).toList(),
                page, size, found.getTotalElements());
    }

    @Transactional(readOnly = true)
    public CouponResponse get(UUID id) {
        return CouponResponse.of(require(id));
    }

    /**
     * Creates a campaign.
     *
     * @throws ConflictException when the code is taken. Case-insensitively — codes are normalised
     *     to upper case, so "welcome10" and "WELCOME10" are the same code and only one can exist.
     */
    @Transactional
    public CouponResponse create(CouponRequest request) {
        String code = Coupon.normalise(request.code());
        if (coupons.existsByCode(code)) {
            throw new ConflictException(ErrorCode.CONFLICT, "A code " + code + " already exists");
        }

        validate(request);

        Instant now = Instant.now();
        Coupon coupon = Coupon.builder()
                .id(UUID.randomUUID())
                .code(code)
                .description(trimToNull(request.description()))
                .type(request.type())
                .value(request.value())
                .currency(currencyFor(request))
                .minimumBasket(request.minimumBasket())
                .maxRedemptions(request.maxRedemptions())
                .redemptionCount(0)
                .perCustomerLimit(request.perCustomerLimit())
                .validFrom(request.validFrom())
                .validUntil(request.validUntil())
                .active(request.active() == null || request.active())
                .createdAt(now)
                .updatedAt(now)
                .build();

        auditService.record("COUPON_CREATED", "COUPON", coupon.getId(),
                "Created " + code + " (" + request.type() + ")");

        log.info("Created coupon {} ({} {})", code, request.type(), request.value());
        return CouponResponse.of(coupons.save(coupon));
    }

    /**
     * Edits a campaign. Everything but the code.
     *
     * @throws BusinessException when the code is being changed, or the cap would be lowered below
     *     what has already gone out
     */
    @Transactional
    public CouponResponse update(UUID id, CouponRequest request) {
        Coupon coupon = require(id);

        if (request.code() != null
                && !Coupon.normalise(request.code()).equals(coupon.getCode())) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "A code cannot be changed once it exists — it is printed on posters and "
                            + "recorded on every order that used it. Create a new one.");
        }

        validate(request);

        // The database has a check constraint for this. Catching it here turns a 500 with a
        // constraint name in it into a sentence that says what the number actually is.
        if (request.maxRedemptions() != null
                && request.maxRedemptions() < coupon.getRedemptionCount()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    coupon.getCode() + " has already been used " + coupon.getRedemptionCount()
                            + " times, so the cap cannot be set to " + request.maxRedemptions()
                            + ". Set it to " + coupon.getRedemptionCount()
                            + " to stop it going out again, or deactivate it.");
        }

        coupon.setDescription(trimToNull(request.description()));
        coupon.setType(request.type());
        coupon.setValue(request.value());
        coupon.setCurrency(currencyFor(request));
        coupon.setMinimumBasket(request.minimumBasket());
        coupon.setMaxRedemptions(request.maxRedemptions());
        coupon.setPerCustomerLimit(request.perCustomerLimit());
        coupon.setValidFrom(request.validFrom());
        coupon.setValidUntil(request.validUntil());
        if (request.active() != null) {
            boolean wasActive = coupon.isActive();
            coupon.setActive(request.active());
            if (wasActive != request.active()) {
                // Its own line, because "when did we turn that off" is a question somebody asks
                // after a week of wondering why redemptions stopped.
                log.warn("Coupon {} is now {}", coupon.getCode(),
                        request.active() ? "live" : "switched off");
                auditService.record("COUPON_" + (request.active() ? "ENABLED" : "DISABLED"),
                        "COUPON", coupon.getId(), coupon.getCode() + " switched "
                                + (request.active() ? "on" : "off"));
            }
        }
        coupon.setUpdatedAt(Instant.now());

        return CouponResponse.of(coupons.save(coupon));
    }

    /**
     * Removes a code nobody has used.
     *
     * @throws ConflictException once it has been redeemed. Those rows are the record of what the
     *     campaign cost, and the operation somebody actually wants is to switch it off — which
     *     stops it working immediately and keeps the history.
     */
    @Transactional
    public void delete(UUID id) {
        Coupon coupon = require(id);

        long used = redemptions.countByCouponId(id);
        if (used > 0) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    coupon.getCode() + " has been used " + used + " time(s). Switch it off "
                            + "instead — that stops it working now and keeps the record of what "
                            + "it cost.");
        }

        coupons.delete(coupon);
        log.info("Deleted unused coupon {}", coupon.getCode());
    }

    /** Who used a code, and what it was worth to them. */
    @Transactional(readOnly = true)
    public List<RedemptionResponse> redemptions(UUID id) {
        require(id);
        return redemptions.findByCouponIdOrderByRedeemedAtDesc(id).stream()
                .map(RedemptionResponse::of)
                .toList();
    }

    // =====================================================================================

    /**
     * The rules that are the same on create and edit.
     *
     * <p>Each of these is refused rather than corrected. A percentage of 1.5 is somebody who typed
     * a percentage where a fraction was expected, and quietly treating it as 100% would create a
     * code that gives everything away.
     */
    private static void validate(CouponRequest request) {
        if (request.type() == DiscountType.PERCENTAGE
                && request.value().compareTo(BigDecimal.ONE) >= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "A percentage is a fraction: 0.10 is 10%. " + request.value()
                            + " would be " + request.value().multiply(BigDecimal.valueOf(100))
                            + "%.");
        }

        if (request.type() == DiscountType.FIXED_AMOUNT
                && (request.currency() == null || request.currency().isBlank())) {
            // Without one there is no way to tell whether "10 off" means euros or dong, and the
            // claim path would have nothing to compare the order's currency against.
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "A fixed-amount code needs a currency");
        }

        if (request.validFrom() != null && request.validUntil() != null
                && !request.validUntil().isAfter(request.validFrom())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "The campaign would end before it started");
        }
    }

    /** Currency is meaningless except for a fixed amount, so it is not stored otherwise. */
    private static String currencyFor(CouponRequest request) {
        return request.type() == DiscountType.FIXED_AMOUNT
                ? request.currency().trim().toUpperCase(java.util.Locale.ROOT)
                : null;
    }

    private Coupon require(UUID id) {
        return coupons.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Coupon not found: " + id));
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
