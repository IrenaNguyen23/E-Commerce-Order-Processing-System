package com.commerceflow.inventoryservice.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.commerceflow.inventoryservice.entity.StockLevel;
import com.commerceflow.inventoryservice.repository.InventoryItemRepository;
import com.commerceflow.inventoryservice.repository.StockLevelRepository;

import lombok.RequiredArgsConstructor;

/**
 * Recomputes the product-level stock figure from the warehouse rows.
 *
 * <h2>Recomputed, never adjusted</h2>
 *
 * <p>{@code stock_levels} is the authoritative figure — how much of one product is in one
 * building. {@code inventory_items} summarises it, and a summary that is incremented alongside its
 * source drifts: one missed path, one partial failure, and it is quietly wrong with nothing to
 * compare it against. This total is what a customer is shown when deciding whether something is in
 * stock, so it is derived every time rather than maintained.
 *
 * <h2>Why this is a component and not a private method</h2>
 *
 * <p>Two callers now need it — the saga, which restocks a cancelled order whole, and returns, which
 * put back part of one. Two copies of a summing rule is how the two drift apart, and the day they
 * do, whichever one is wrong is wrong invisibly.
 */
@Component
@RequiredArgsConstructor
public class InventorySummaryRefresher {

    private final StockLevelRepository stockLevels;
    private final InventoryItemRepository inventoryItems;

    /** Recomputes the summary for each product from its warehouse rows. */
    public void refresh(List<UUID> productIds) {
        for (UUID productId : productIds) {
            List<StockLevel> levels = stockLevels.findByProductId(productId);
            int available = levels.stream().mapToInt(StockLevel::getAvailableQuantity).sum();
            int reserved = levels.stream().mapToInt(StockLevel::getReservedQuantity).sum();

            inventoryItems.findById(productId).ifPresent(item -> {
                item.setAvailableQuantity(available);
                item.setReservedQuantity(reserved);
                item.setUpdatedAt(Instant.now());
                inventoryItems.save(item);
            });
        }
    }
}
