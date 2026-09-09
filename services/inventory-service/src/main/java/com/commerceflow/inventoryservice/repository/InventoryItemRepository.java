package com.commerceflow.inventoryservice.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.commerceflow.inventoryservice.entity.InventoryItem;

import jakarta.persistence.LockModeType;

/** Persistence port for the stock ledger. */
@Repository
public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

    Optional<InventoryItem> findBySkuIgnoreCase(String sku);

    List<InventoryItem> findByProductIdIn(List<UUID> productIds);

    /**
     * Claims the rows for the given products with a write lock, ordered by product id.
     *
     * <p>A pessimistic lock rather than an optimistic retry: two orders competing for the last
     * unit is normal traffic, not an exceptional case, and the ordered acquisition guarantees no
     * two transactions can deadlock against each other.
     */
    @org.springframework.data.jpa.repository.Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM InventoryItem i WHERE i.productId IN :productIds ORDER BY i.productId")
    List<InventoryItem> lockAllByProductIds(@Param("productIds") List<UUID> productIds);

    @Query("SELECT i FROM InventoryItem i WHERE i.availableQuantity <= i.reorderLevel")
    List<InventoryItem> findBelowReorderLevel();
}
