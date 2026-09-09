package com.commerceflow.inventoryservice.dto;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A catalogue section.
 *
 * <p>{@code productCount} counts what is filed under it, and it is the number that decides whether
 * deleting is offered — a section with products behind it can be hidden but not removed.
 */
@Schema(description = "A catalogue category")
public record CategoryResponse(
        UUID id,
        String slug,
        String name,
        String description,
        UUID parentId,
        int position,
        String imageUrl,
        boolean active,
        @Schema(description = "How many products are filed under this section")
        long productCount,
        @Schema(description = "Subsections, for a top-level category")
        List<CategoryResponse> children) {
}
