package com.commerceflow.inventoryservice.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.commerceflow.inventoryservice.entity.StockLevel;

import jakarta.persistence.LockModeType;

@Repository
public interface StockLevelRepository extends JpaRepository<StockLevel, UUID> {

    List<StockLevel> findByProductId(UUID productId);

    List<StockLevel> findByWarehouseId(UUID warehouseId);

    Optional<StockLevel> findByWarehouseIdAndProductId(UUID warehouseId, UUID productId);

    /**
     * Locks every warehouse row for a set of products, in a fixed order.
     *
     * <p>The ordering is the whole point. Two customers racing for the last unit is ordinary
     * traffic, and two transactions taking the same rows in different orders is a deadlock. Sorted
     * by product then warehouse, every caller takes them the same way round.
     *
     * <p>{@code PESSIMISTIC_WRITE} rather than optimistic: a version clash here means a failed
     * checkout for a customer who did nothing wrong, and stock is the one thing worth blocking
     * briefly to get right.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StockLevel s WHERE s.productId IN :productIds "
            + "ORDER BY s.productId ASC, s.warehouseId ASC")
    List<StockLevel> lockAllByProductIds(@Param("productIds") List<UUID> productIds);

    /** Everything in one building that has fallen to or below its reorder point. */
    @Query("SELECT s FROM StockLevel s WHERE s.availableQuantity <= s.reorderLevel")
    List<StockLevel> findBelowReorderLevel();
}
