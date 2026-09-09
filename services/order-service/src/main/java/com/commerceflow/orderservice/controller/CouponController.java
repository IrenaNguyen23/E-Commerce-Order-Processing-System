package com.commerceflow.orderservice.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerceflow.common.dto.ApiResponse;
import com.commerceflow.common.dto.PageResponse;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.orderservice.dto.PreviewCouponRequest;
import com.commerceflow.orderservice.promotion.CouponAdminService;
import com.commerceflow.orderservice.promotion.CouponPreview;
import com.commerceflow.orderservice.promotion.CouponRequest;
import com.commerceflow.orderservice.promotion.CouponResponse;
import com.commerceflow.orderservice.promotion.CouponService;
import com.commerceflow.orderservice.promotion.RedemptionResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * Discount codes: one customer-facing endpoint, and the back office behind it.
 *
 * <p>The preview deliberately does not claim anything. A basket page that spent a redemption every
 * time somebody typed a code to see what it did would exhaust a limited campaign on curiosity
 * alone.
 *
 * <p>The consequence is that a code can pass here and be refused at checkout, when the last one
 * has gone in between. That is not a flaw to paper over — it is what a limited campaign means, and
 * the checkout says so in as many words rather than quietly placing the order at full price.
 */
@Validated
@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Coupons", description = "Discount codes")
public class CouponController {

    private final CouponService couponService;
    private final CouponAdminService couponAdminService;

    @PostMapping("/preview")
    @Operation(summary = "What would this code be worth?",
            description = """
                    Validates a code against a basket and returns what it would take off, \
                    without spending a redemption.

                    The saving comes back as two figures rather than one. A reduction on the \
                    goods lowers the tax with it; free delivery does not touch the tax at all. \
                    Collapsing them into a single "you save" number is how a basket page ends up \
                    disagreeing with the checkout total for reasons no customer can work out.

                    A code that cannot be used is refused with `422` and a message that says \
                    which reason — expired, not started, fully redeemed, basket too small, \
                    already used by this customer. "Invalid code" for all of them leaves the \
                    customer unable to tell a typo from an expiry.""")
    public ResponseEntity<ApiResponse<CouponPreview>> preview(
            @Valid @RequestBody PreviewCouponRequest request,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        CouponPreview preview = couponService.preview(
                request.code(),
                caller == null ? null : caller.userId(),
                request.basketAmount(),
                request.shippingAmount() == null ? BigDecimal.ZERO : request.shippingAmount(),
                request.currency() == null ? "EUR" : request.currency());

        return ResponseEntity.ok(ApiResponse.ok(preview));
    }

    // =====================================================================================
    // Back office
    // =====================================================================================

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List codes",
            description = "Newest first — a campaign somebody is looking for is nearly always one "
                    + "they set up recently.")
    public ResponseEntity<ApiResponse<PageResponse<CouponResponse>>> list(
            @Parameter(description = "Filter by whether the code is switched on")
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        return ResponseEntity.ok(ApiResponse.ok(couponAdminService.list(active, page, size)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "One code")
    public ResponseEntity<ApiResponse<CouponResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(couponAdminService.get(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a code",
            description = """
                    `value` is a **fraction** for a percentage: `0.10` is ten per cent. A fixed \
                    amount is an amount in `currency`, and needs one — without it there is no way \
                    to tell whether "10 off" means euros or dong.

                    Accepting `10` and guessing which was meant is the kind of convenience that \
                    eventually creates a code worth ten times what somebody intended, so a \
                    percentage of 1 or more is refused rather than interpreted.""")
    public ResponseEntity<ApiResponse<CouponResponse>> create(
            @Valid @RequestBody CouponRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(couponAdminService.create(request), "Code created"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Edit a code",
            description = """
                    Everything but the code itself, which is immutable: it is printed on posters \
                    and recorded as text on every order that used it, so renaming it makes all of \
                    that refer to something that no longer exists.

                    The value, the window and the caps **are** editable on a live campaign — one \
                    doing better or worse than expected gets adjusted, and refusing that sends \
                    operators to the database. Changes affect the next order and nothing already \
                    placed.

                    Lowering the cap below what has already been redeemed is refused with the \
                    actual number, rather than arriving as a constraint violation.""")
    public ResponseEntity<ApiResponse<CouponResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody CouponRequest request) {

        return ResponseEntity.ok(
                ApiResponse.ok(couponAdminService.update(id, request), "Code updated"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete an unused code",
            description = "Refused with 409 once it has been redeemed — those rows are the record "
                    + "of what the campaign cost. Switch it off instead, which stops it working "
                    + "immediately and keeps the history.")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        couponAdminService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/redemptions")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Who used a code",
            description = "And what it was worth to them — which can be less than the code's face "
                    + "value when the basket was smaller than the discount. That figure, not the "
                    + "value, is what the campaign cost.")
    public ResponseEntity<ApiResponse<List<RedemptionResponse>>> redemptions(
            @PathVariable UUID id) {

        return ResponseEntity.ok(ApiResponse.ok(couponAdminService.redemptions(id)));
    }
}
