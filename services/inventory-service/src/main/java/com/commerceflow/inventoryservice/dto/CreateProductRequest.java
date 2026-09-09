package com.commerceflow.inventoryservice.dto;

import java.math.BigDecimal;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Payload of {@code POST /api/products}. */
@Schema(description = "New catalogue product")
public record CreateProductRequest(

        @Schema(example = "CF-LAPTOP-001", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 64)
        @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "sku may only contain letters, digits, dot, dash and underscore")
        String sku,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 200) String name,

        @Size(max = 2000) String description,

        @Schema(description = "Which section to file it under. Send the id, or the slug in "
                + "categorySlug -- a section has to exist first, so that a typo produces an "
                + "error rather than a fourth spelling of Computers.")
        UUID categoryId,

        @Schema(description = "Alternative to categoryId, for scripts and imports",
                example = "computers")
        @Size(max = 100) String categorySlug,

        @Schema(example = "1299.00", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @DecimalMin(value = "0.0", inclusive = false, message = "price must be positive")
        @Digits(integer = 15, fraction = 4)
        BigDecimal price,

        @Schema(example = "EUR", defaultValue = "EUR")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be an ISO-4217 code")
        String currency,

        @Size(max = 500) String imageUrl,

        @Schema(example = "100", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Min(value = 0, message = "initialQuantity cannot be negative")
        Integer initialQuantity,

        @Schema(example = "10")
        @Min(value = 0, message = "reorderLevel cannot be negative")
        Integer reorderLevel) {

    public String currencyOrDefault() {
        return currency == null || currency.isBlank() ? "EUR" : currency;
    }

    public int reorderLevelOrDefault() {
        return reorderLevel == null ? 0 : reorderLevel;
    }
}
