package com.commerceflow.orderservice.promotion;

import java.math.BigDecimal;
import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Creating or editing a discount code.
 *
 * <h2>{@code value} means different things per type, and the API does not hide that</h2>
 *
 * <p>A percentage is a <b>fraction</b>: {@code 0.10} is ten per cent. A fixed amount is an amount
 * in {@code currency}. Free delivery ignores it entirely.
 *
 * <p>Accepting {@code 10} and guessing whether it meant ten per cent or ten euros is the kind of
 * convenience that eventually creates a code worth ten times what somebody intended. The client is
 * responsible for the conversion, and the admin form does it visibly.
 */
@Schema(description = "A discount code")
public record CouponRequest(

        @Schema(example = "WELCOME10", requiredMode = Schema.RequiredMode.REQUIRED,
                description = "What the customer types. Stored and matched upper case, and "
                        + "immutable once the code exists — it is printed on posters and quoted "
                        + "in every order that used it.")
        @NotBlank(message = "code is required")
        @Size(max = 40)
        String code,

        @Schema(example = "10% off your first order")
        @Size(max = 200)
        String description,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "PERCENTAGE, FIXED_AMOUNT or FREE_SHIPPING")
        @NotNull(message = "type is required")
        DiscountType type,

        @Schema(example = "0.1000",
                description = "A fraction for PERCENTAGE (0.1000 is 10%), an amount for "
                        + "FIXED_AMOUNT, ignored for FREE_SHIPPING")
        @NotNull(message = "value is required")
        @DecimalMin(value = "0.0", message = "value cannot be negative")
        BigDecimal value,

        @Schema(example = "EUR",
                description = "Required for FIXED_AMOUNT. A fixed-amount code is refused on an "
                        + "order in another currency rather than converted — inventing an "
                        + "exchange rate at checkout is worse than refusing the code.")
        @Size(min = 3, max = 3)
        String currency,

        @Schema(description = "Basket value, before the discount, below which it does not apply")
        BigDecimal minimumBasket,

        @Schema(description = "Total uses across everybody. Null for an unlimited campaign. "
                + "Cannot be lowered below what has already been redeemed.")
        @Min(1)
        Integer maxRedemptions,

        @Schema(description = "Uses per customer. Null for as often as they like.")
        @Min(1)
        Integer perCustomerLimit,

        Instant validFrom,
        Instant validUntil,

        @Schema(description = "An off switch for a campaign that has to stop now, ahead of its "
                + "window. Defaults to true.")
        Boolean active) {
}
