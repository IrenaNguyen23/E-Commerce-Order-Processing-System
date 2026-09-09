package com.commerceflow.paymentservice.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.commerceflow.paymentservice.entity.PaymentMethod;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Payload of {@code POST /api/payments/process}. */
@Schema(description = "Manual payment request")
public record ProcessPaymentRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "orderId is required")
        UUID orderId,

        @Schema(example = "CF-20260826-000123")
        @Size(max = 32) String orderNumber,

        @Schema(example = "1899.00", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "amount must be positive")
        @Digits(integer = 15, fraction = 4)
        BigDecimal amount,

        @Schema(example = "EUR")
        @Pattern(regexp = "^$|^[A-Z]{3}$", message = "currency must be an ISO-4217 code")
        String currency,

        @Schema(defaultValue = "CARD") PaymentMethod method) {

    public String currencyOrDefault(String fallback) {
        return currency == null || currency.isBlank() ? fallback : currency;
    }

    public PaymentMethod methodOrDefault() {
        return method == null ? PaymentMethod.CARD : method;
    }
}
