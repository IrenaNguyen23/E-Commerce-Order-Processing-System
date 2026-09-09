package com.commerceflow.inventoryservice.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.inventoryservice.dto.StockLevelResponse;
import com.commerceflow.inventoryservice.dto.WarehouseRequest;
import com.commerceflow.inventoryservice.dto.WarehouseResponse;
import com.commerceflow.inventoryservice.entity.InventoryItem;
import com.commerceflow.inventoryservice.entity.StockLevel;
import com.commerceflow.inventoryservice.entity.Warehouse;
import com.commerceflow.inventoryservice.repository.InventoryItemRepository;
import com.commerceflow.inventoryservice.repository.ProductRepository;
import com.commerceflow.inventoryservice.repository.StockLevelRepository;
import com.commerceflow.inventoryservice.repository.WarehouseRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Buildings, and how much of each product is in them.
 *
 * <h2>Closing a building is not deleting it</h2>
 *
 * <p>There is no delete. A warehouse appears on reservations, on shipments and in the history of
 * every order it filled; removing the row would orphan all of that to tidy up a list. Setting it
 * inactive stops new allocations and leaves everything already promised alone, which is what
 * somebody closing a building actually wants during the weeks it takes.
 *
 * <h2>Adjusting stock never touches held units</h2>
 *
 * <p>Same rule as the product-level correction, and for the same reason: units held by a running
 * saga are not the back office's to move. Taking them would break the compensation arithmetic —
 * a release would then put back stock that had already been counted away.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarehouseService {

    private final WarehouseRepository warehouses;
    private final StockLevelRepository stockLevels;
    private final InventoryItemRepository inventoryItems;
    private final ProductRepository products;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<WarehouseResponse> list(boolean activeOnly) {
        List<Warehouse> found = activeOnly
                ? warehouses.findByActiveTrueOrderByPriorityAscCodeAsc()
                : warehouses.findAllByOrderByPriorityAscCodeAsc();
        return found.stream().map(WarehouseService::toResponse).toList();
    }

    @Transactional
    public WarehouseResponse create(WarehouseRequest request) {
        String code = Warehouse.normaliseCode(request.code());
        if (warehouses.existsByCodeIgnoreCase(code)) {
            throw new ConflictException(ErrorCode.CONFLICT, "A warehouse already uses " + code);
        }

        Instant now = Instant.now();
        Warehouse warehouse = warehouses.save(Warehouse.builder()
                .id(UUID.randomUUID())
                .code(code)
                .name(request.name().trim())
                .countryCode(request.countryCode().trim().toUpperCase(java.util.Locale.ROOT))
                .city(trimToNull(request.city()))
                .priority(request.priority() == null ? 100 : request.priority())
                .active(request.active() == null || request.active())
                .createdAt(now)
                .updatedAt(now)
                .build());

        log.info("Opened warehouse {} ({})", warehouse.getCode(), warehouse.getCountryCode());
        return toResponse(warehouse);
    }

    @Transactional
    public WarehouseResponse update(UUID id, WarehouseRequest request) {
        Warehouse warehouse = require(id);

        if (request.code() != null
                && !Warehouse.normaliseCode(request.code()).equals(warehouse.getCode())) {
            // The code is on paperwork, on labels and in every log line about this building.
            // Changing it makes historical records refer to something that no longer exists.
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "A warehouse code cannot be changed once it exists");
        }

        warehouse.setName(request.name().trim());
        warehouse.setCountryCode(request.countryCode().trim().toUpperCase(java.util.Locale.ROOT));
        warehouse.setCity(trimToNull(request.city()));
        if (request.priority() != null) {
            warehouse.setPriority(request.priority());
        }
        if (request.active() != null) {
            boolean wasActive = warehouse.isActive();
            warehouse.setActive(request.active());
            if (wasActive != request.active()) {
                // Worth its own line: "when did we stop shipping from Milan" is a question
                // somebody asks a fortnight later, when orders are piling up somewhere else.
                log.warn("Warehouse {} is now {}", warehouse.getCode(),
                        request.active() ? "taking orders" : "closed to new orders");
            }
        }
        warehouse.setUpdatedAt(Instant.now());

        return toResponse(warehouses.save(warehouse));
    }

    /** What is in one building. */
    @Transactional(readOnly = true)
    public List<StockLevelResponse> contents(UUID warehouseId) {
        require(warehouseId);
        return stockLevels.findByWarehouseId(warehouseId).stream()
                .map(level -> toResponse(level, skuOf(level.getProductId())))
                .toList();
    }

    /** Where one product is, across every building. */
    @Transactional(readOnly = true)
    public List<StockLevelResponse> locate(UUID productId) {
        return stockLevels.findByProductId(productId).stream()
                .map(level -> toResponse(level, skuOf(level.getProductId())))
                .toList();
    }

    /**
     * Sets how many units of a product are in a building.
     *
     * <p>Creates the row if this building has never carried the product. Held units are left
     * exactly as they are; see the class comment.
     */
    @Transactional
    public StockLevelResponse setStock(UUID warehouseId, UUID productId, int quantity,
            Integer reorderLevel) {

        require(warehouseId);
        if (!products.existsById(productId)) {
            throw new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND,
                    "Product not found: " + productId);
        }

        StockLevel level = stockLevels.findByWarehouseIdAndProductId(warehouseId, productId)
                .orElseGet(() -> StockLevel.builder()
                        .id(UUID.randomUUID())
                        .warehouseId(warehouseId)
                        .productId(productId)
                        .availableQuantity(0)
                        .reservedQuantity(0)
                        .updatedAt(Instant.now())
                        .build());

        level.setAvailable(quantity);
        if (reorderLevel != null) {
            level.setReorderLevel(Math.max(0, reorderLevel));
        }
        StockLevel saved = stockLevels.save(level);

        refreshSummary(productId);

        auditService.record("STOCK_SET", "STOCK_LEVEL", productId,
                "Set to " + quantity + " unit(s) in warehouse " + warehouseId);

        log.info("Set {} to {} unit(s) in warehouse {}", productId, quantity, warehouseId);
        return toResponse(saved, skuOf(productId));
    }

    /**
     * Opens a stock row for a product in the default building.
     *
     * <p>Called when a product is created, so that a new product has stock <em>somewhere</em>
     * rather than an opening quantity that exists only in the summary and can never be allocated.
     *
     * @return the warehouse it went into, or {@code null} when no warehouse exists at all — which
     *     is possible on a fresh install and is better reported by the caller than guessed at here
     */
    @Transactional
    public UUID openStock(UUID productId, int quantity, int reorderLevel) {
        Warehouse target = warehouses.findByActiveTrueOrderByPriorityAscCodeAsc().stream()
                .findFirst()
                .orElse(null);

        if (target == null) {
            log.warn("Product {} was created with {} unit(s) but there is no active warehouse to "
                    + "put them in; it will show as out of stock", productId, quantity);
            return null;
        }

        stockLevels.save(StockLevel.builder()
                .id(UUID.randomUUID())
                .warehouseId(target.getId())
                .productId(productId)
                .availableQuantity(Math.max(0, quantity))
                .reservedQuantity(0)
                .reorderLevel(Math.max(0, reorderLevel))
                .updatedAt(Instant.now())
                .build());

        return target.getId();
    }

    /** Everything at or below its reorder point, per building. */
    @Transactional(readOnly = true)
    public List<StockLevelResponse> lowStock() {
        return stockLevels.findBelowReorderLevel().stream()
                .map(level -> toResponse(level, skuOf(level.getProductId())))
                .toList();
    }

    // =====================================================================================

    /** Recomputes the product-level summary; see {@code InventoryReservationService}. */
    private void refreshSummary(UUID productId) {
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

    private Warehouse require(UUID id) {
        return warehouses.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Warehouse not found: " + id));
    }

    private String skuOf(UUID productId) {
        return inventoryItems.findById(productId).map(InventoryItem::getSku).orElse(null);
    }

    private static WarehouseResponse toResponse(Warehouse warehouse) {
        return new WarehouseResponse(warehouse.getId(), warehouse.getCode(), warehouse.getName(),
                warehouse.getCountryCode(), warehouse.getCity(), warehouse.getPriority(),
                warehouse.isActive(), warehouse.getUpdatedAt());
    }

    private static StockLevelResponse toResponse(StockLevel level, String sku) {
        return new StockLevelResponse(level.getWarehouseId(), level.getProductId(), sku,
                level.getAvailableQuantity(), level.getReservedQuantity(),
                level.getReorderLevel(), level.isBelowReorderLevel(), level.getUpdatedAt());
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
