package com.commerceflow.inventoryservice.dto;

import java.math.BigDecimal;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload of {@code PUT /api/products/{id}}.
 *
 * <p>Notably absent: <b>SKU</b> and <b>stock</b>.
 *
 * <p>The SKU is how warehouses, invoices and integrations refer to this product. Changing it here
 * would silently break every one of them, so it is fixed at creation — a product that needs a
 * different SKU is a different product.
 *
 * <p>Stock has its own endpoint because it has its own rules: {@code PUT /{id}/stock} only moves
 * {@code availableQuantity} and never touches units a running saga is holding. Editing it as an
 * ordinary field here would let a back-office correction quietly overwrite a reservation and break
 * the compensation arithmetic.
 *
 * <p>Every other field is a straight replacement, including {@code active} — which is how a
 * product is taken off sale. There is no delete: orders reference products, and deleting one
 * would rewrite history that a customer has a receipt for.
 */
@Schema(description = "Replacement values for an existing product")
public record UpdateProductRequest(

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

        @Size(max = 500) String imageUrl,

        @Schema(
                description = "False takes the product off the storefront. Existing orders are "
                        + "unaffected — they carry their own snapshot of name and price.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Boolean active) {
}
