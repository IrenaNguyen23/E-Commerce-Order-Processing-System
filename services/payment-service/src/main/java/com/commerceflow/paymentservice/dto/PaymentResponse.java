package com.commerceflow.paymentservice.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** A payment as returned by the API. */
@Schema(description = "Payment")
public record PaymentResponse(
        UUID id,
        UUID orderId,
        String orderNumber,
        UUID userId,
        BigDecimal amount,
        String currency,
        @Schema(example = "COMPLETED", description = "PENDING, COMPLETED, FAILED or REFUNDED")
        String status,
        @Schema(example = "CARD") String method,
        @Schema(description = "Acquirer reference, present once the charge is approved")
        String transactionId,
        String failureReason,

        @Schema(description = "Authorises the browser to finish this charge, when one is "
                + "outstanding. Present only while the payment is PENDING and only for the "
                + "customer it belongs to: it grants exactly one thing, paying this intent, and "
                + "nothing once the charge settles. Null with the simulated acquirer, which "
                + "settles before anyone could use it.")
        String clientSecret,

        Instant createdAt,
        Instant processedAt) {
}
