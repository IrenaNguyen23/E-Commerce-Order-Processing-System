package com.commerceflow.inventoryservice.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One image in a product's gallery, without the bytes.
 *
 * <p>{@code url} is a path rather than an absolute address, so the same response works behind the
 * gateway, in local development and on whatever host the storefront ends up on. A stored absolute
 * URL is a stored guess about where the service will be deployed.
 */
@Schema(description = "A product image")
public record ProductImageResponse(
        UUID id,
        @Schema(description = "Where to fetch it. Immutable: the bytes behind an id never change, "
                + "so it can be cached indefinitely.",
                example = "/api/products/8f14.../images/3c20...")
        String url,
        @Schema(description = "Determined from the bytes, not from what the upload claimed")
        String contentType,
        long sizeBytes,
        @Schema(description = "What a screen reader says; null for a decorative image")
        String altText,
        int position,
        @Schema(description = "True for the image shown on a listing tile")
        boolean primary,
        Instant createdAt) {
}
