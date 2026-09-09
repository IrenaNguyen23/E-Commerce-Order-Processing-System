package com.commerceflow.orderservice.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** An order as served by the query side. */
@Schema(description = "Order")
public record OrderResponse(
        UUID id,
        @Schema(example = "CF-20260826-000123") String orderNumber,
        UUID userId,
        String userEmail,
        @Schema(example = "COMPLETED",
                description = "CREATED, INVENTORY_RESERVED, PAID, COMPLETED or CANCELLED")
        String status,
        @Schema(description = "The goods at list price, before anything came off")
        BigDecimal subtotalAmount,
        @Schema(description = "Everything that came off, across the order")
        BigDecimal discountTotal,
        @Schema(description = "Tax, summed from the per-line amounts")
        BigDecimal taxTotal,
        @Schema(description = "What delivery cost; zero when free or collected")
        BigDecimal shippingAmount,
        @Schema(description = "What was charged: subtotal - discount + tax + delivery")
        BigDecimal totalAmount,
        String currency,
        @Schema(description = "One line, as it was frozen at checkout")
        String shippingAddress,
        @Schema(description = "Where it is going, as submitted and then kept")
        DeliveryDestination destination,
        @Schema(description = "How it travels and when it was said to arrive")
        DeliverySummary delivery,
        int itemCount,
        List<OrderItemResponse> items,
        UUID paymentId,
        @Schema(description = "The discount code that was used, if any", example = "WELCOME10")
        String couponCode,
        @Schema(description = "Saga step that failed, when the order was cancelled")
        String failedStep,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {
}
