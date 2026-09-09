package com.commerceflow.orderservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Payload of {@code POST /api/orders/{id}/cancel}.
 *
 * <p>Optional, and worth filling in. The reason is recorded on the order, on the reservation and
 * on the payment, and it is what the customer is told — "cancelled at your request" reads very
 * differently from a bare cancellation, especially on an order that was already paid for.
 */
@Schema(description = "Why the order is being cancelled")
public record CancelOrderRequest(

        @Size(max = 255)
        @Schema(example = "Ordered the wrong size")
        String reason) {
}
