package com.commerceflow.orderservice.dto;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One line of an order as returned to the customer.
 *
 * <p>Also the shape stored in the read model JSON column, so the projection and the API response
 * cannot drift apart.
 */
@Schema(description = "Order line")
public record OrderItemResponse(
        UUID productId,
        String sku,
        String productName,
        @Schema(description = "Category at order time; the catalogue may have reorganised since")
        String productCategory,
        @Schema(description = "The image the customer saw, not the product's current one")
        String productImageUrl,
        int quantity,
        @Schema(description = "Catalogue price at order time, before any discount")
        BigDecimal listPrice,
        @Schema(description = "Per unit reduction, from a promotion or a coupon")
        BigDecimal discountAmount,
        @Schema(description = "What was charged per unit: listPrice - discountAmount")
        BigDecimal unitPrice,
        BigDecimal subtotal,
        @Schema(description = "What the tax was called where this went: VAT, BTW, GST")
        String taxName,
        @Schema(description = "The rate applied as a fraction; 0.2100 is 21%")
        BigDecimal taxRate,
        @Schema(description = "Tax on this line, rounded here and summed into the order total")
        BigDecimal taxAmount) {
}
