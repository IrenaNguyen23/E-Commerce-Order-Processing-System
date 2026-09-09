package com.commerceflow.orderservice.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The slice of Inventory Service's product projection that Order Service needs in order to price
 * a basket. Deliberately narrow: the two services stay decoupled from each other's full schema.
 */
public record CatalogProduct(
        UUID id,
        String sku,
        String name,
        // Snapshotted onto the order line, which is why they are here at all. Without them the
        // order screen would have to read the live catalogue, and an order from six months ago
        // would show a picture the customer never saw.
        String category,
        /**
         * The category's stable identifier.
         *
         * <p>Carried alongside the display name because the two are used for different things and
         * only one of them is safe to key on. The name is what appears on an invoice; the slug is
         * what the tax rate card is looked up by. Keying tax on the name would mean that renaming
         * "Computers" to "Laptops & desktops" changed the tax charged on everything inside it,
         * with no error anywhere — just a different figure on the next order.
         */
        String categorySlug,
        String imageUrl,
        BigDecimal price,
        String currency,
        boolean active,
        int availableQuantity) {
}
