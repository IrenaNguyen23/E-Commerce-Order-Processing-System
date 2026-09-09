package com.commerceflow.notificationservice.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** A notification as returned by the API. */
@Schema(description = "Notification")
public record NotificationResponse(
        UUID id,
        UUID userId,
        @Schema(description = "Business entity this notification is about, typically an order id")
        UUID referenceId,
        @Schema(example = "ORDER_CONFIRMED") String type,
        @Schema(example = "EMAIL") String channel,
        String recipient,
        String subject,
        String content,
        @Schema(example = "SENT", description = "PENDING, SENT or FAILED") String status,
        String failureReason,
        int retryCount,
        Instant createdAt,
        Instant sentAt) {
}
