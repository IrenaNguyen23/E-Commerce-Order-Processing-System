package com.commerceflow.authservice.dto;

import java.time.Instant;
import java.util.UUID;

import com.commerceflow.authservice.entity.AddressType;

import io.swagger.v3.oas.annotations.media.Schema;

/** A saved address, as the customer's own client sees it. */
@Schema(description = "A saved address")
public record AddressResponse(
        UUID id,
        String label,
        AddressType type,
        String recipientName,
        String phone,
        String line1,
        String line2,
        String city,
        String region,
        String postalCode,
        String countryCode,
        @Schema(description = "One line, ready to print on a label") String formatted,
        boolean isDefault,
        Instant createdAt,
        Instant updatedAt) {
}
