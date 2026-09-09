package com.commerceflow.inventoryservice.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** A warehouse. */
@Schema(description = "A warehouse")
public record WarehouseResponse(
        UUID id,
        @Schema(example = "AMS") String code,
        String name,
        String countryCode,
        String city,
        @Schema(description = "Lower goes first when more than one could fill a line")
        int priority,
        @Schema(description = "Whether new orders may be allocated from here")
        boolean active,
        Instant updatedAt) {
}
