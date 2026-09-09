package com.commerceflow.inventoryservice.mapper;

import org.springframework.stereotype.Component;

import com.commerceflow.inventoryservice.dto.ProductResponse;
import com.commerceflow.inventoryservice.entity.Category;
import com.commerceflow.inventoryservice.entity.InventoryItem;
import com.commerceflow.inventoryservice.entity.Product;

/**
 * Joins the catalogue row and its stock ledger row into the public projection.
 *
 * <p>Hand written rather than generated: the mapping has two sources, so an explicit method is
 * shorter and clearer than the annotations that would be needed to disambiguate it.
 */
@Component
public class ProductMapper {

    public ProductResponse toResponse(Product product, InventoryItem item) {
        return toResponse(product, item, null);
    }

    /**
     * @param category the section this product is filed under, or {@code null} when it is unfiled
     *     or the caller has not resolved it. Passed in rather than looked up here: a search
     *     returning fifty products would otherwise be fifty extra queries against a table with a
     *     few dozen rows in it.
     */
    public ProductResponse toResponse(Product product, InventoryItem item, Category category) {
        return toResponse(product, item, category, null);
    }

    /**
     * @param uploadedImageUrl the product's tile image, when it has uploads. Takes precedence over
     *     the {@code imageUrl} column: a product whose photographs were uploaded should show one
     *     of them, not whatever placeholder was set before. Products with no uploads keep the
     *     column, which is how the seeded catalogue's inline placeholders survive.
     */
    public ProductResponse toResponse(Product product, InventoryItem item, Category category,
            String uploadedImageUrl) {
        int available = item == null ? 0 : item.getAvailableQuantity();
        int reserved = item == null ? 0 : item.getReservedQuantity();

        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getCategoryId(),
                category == null ? null : category.getSlug(),
                category == null ? null : category.getName(),
                product.getPrice(),
                product.getCurrency(),
                uploadedImageUrl != null ? uploadedImageUrl : product.getImageUrl(),
                product.isActive(),
                available,
                reserved,
                available > 0,
                product.getRatingAverage(),
                product.getRatingCount(),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}
