package com.commerceflow.inventoryservice.dto;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** Payload of {@code POST /api/products/lookup}. */
@Schema(description = "Batch product lookup")
public record ProductLookupRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "productIds is required")
        @Size(max = 100, message = "at most 100 products may be looked up at once")
        List<UUID> productIds) {
}
