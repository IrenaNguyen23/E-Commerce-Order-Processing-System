package com.commerceflow.inventoryservice.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.commerceflow.inventoryservice.entity.StockLevel;
import com.commerceflow.inventoryservice.entity.Warehouse;

/**
 * Decides which building each unit of an order comes out of.
 *
 * <h2>The rules, in order</h2>
 *
 * <ol>
 *   <li><b>A single building, if one can fill the whole line.</b> Splitting a line means two
 *       parcels, two tracking numbers and two chances to go wrong, for a customer who ordered one
 *       thing. It is worth avoiding even when it costs a little on the other rules.
 *   <li><b>The destination country first.</b> Shipping from inside the country the parcel is going
 *       to avoids a customs form and usually a day.
 *   <li><b>Then priority.</b> An explicit number, because "which building should this come from"
 *       depends on carrier contracts and what a shop is clearing, not on a distance.
 *   <li><b>Then the fullest.</b> Among equals, take from where there is most, which leaves the
 *       thinner buildings able to fill their own local orders.
 * </ol>
 *
 * <h2>Splitting is a fallback, not a strategy</h2>
 *
 * <p>When no single building can fill a line, the order is taken from several in the same
 * preference order until it is covered. That is better than refusing — the shop does have the
 * stock — but it is second choice, and the caller can see it happened because the reservation ends
 * up with more than one row for the product.
 *
 * <h2>All or nothing, per line</h2>
 *
 * <p>If the total across every active warehouse is short, this returns nothing for that line
 * rather than a partial allocation. A partly-filled line is an order the customer did not place,
 * and deciding to send four of the five they wanted is not a decision an allocator should make on
 * its own.
 */
final class StockAllocator {

    private StockAllocator() {
    }

    /** One product taken from one building. */
    record Allocation(UUID warehouseId, UUID productId, int quantity) {
    }

    /**
     * Works out where each product comes from.
     *
     * @param demand how many of each product the order needs
     * @param stockByProduct the locked warehouse rows, grouped by product
     * @param warehouses the buildings that may be allocated from, already filtered to active ones
     * @param destinationCountry where the parcel is going; may be {@code null}
     * @return one allocation per building used, or an empty list for any product that cannot be
     *     covered at all — the caller decides what to tell the customer
     */
    static List<Allocation> allocate(Map<UUID, Integer> demand,
            Map<UUID, List<StockLevel>> stockByProduct,
            List<Warehouse> warehouses,
            String destinationCountry) {

        Map<UUID, Warehouse> byId = new java.util.HashMap<>();
        warehouses.forEach(warehouse -> byId.put(warehouse.getId(), warehouse));

        List<Allocation> allocations = new ArrayList<>();

        for (Map.Entry<UUID, Integer> entry : demand.entrySet()) {
            UUID productId = entry.getKey();
            int wanted = entry.getValue();

            List<StockLevel> candidates = new ArrayList<>(
                    stockByProduct.getOrDefault(productId, List.of()));
            candidates.removeIf(level -> !byId.containsKey(level.getWarehouseId())
                    || level.getAvailableQuantity() <= 0);

            candidates.sort(preference(byId, destinationCountry));

            // First choice: one building that can take the whole line.
            StockLevel single = candidates.stream()
                    .filter(level -> level.getAvailableQuantity() >= wanted)
                    .findFirst()
                    .orElse(null);

            if (single != null) {
                allocations.add(new Allocation(single.getWarehouseId(), productId, wanted));
                continue;
            }

            // Second choice: spread it, in the same preference order.
            int outstanding = wanted;
            List<Allocation> split = new ArrayList<>();
            for (StockLevel level : candidates) {
                if (outstanding <= 0) {
                    break;
                }
                int take = Math.min(outstanding, level.getAvailableQuantity());
                split.add(new Allocation(level.getWarehouseId(), productId, take));
                outstanding -= take;
            }

            if (outstanding > 0) {
                // Short across everywhere. Nothing is allocated for this product — a partly
                // filled line is an order the customer did not place.
                continue;
            }
            allocations.addAll(split);
        }

        return allocations;
    }

    /** Destination country, then priority, then the fullest building. */
    private static Comparator<StockLevel> preference(Map<UUID, Warehouse> byId,
            String destinationCountry) {

        return Comparator
                .<StockLevel, Boolean>comparing(level ->
                        !byId.get(level.getWarehouseId()).isIn(destinationCountry))
                .thenComparingInt(level -> byId.get(level.getWarehouseId()).getPriority())
                .thenComparing(Comparator.comparingInt(StockLevel::getAvailableQuantity).reversed())
                // Warehouse id last, so an allocation is reproducible rather than depending on
                // whatever order the database happened to return equal rows in.
                .thenComparing(StockLevel::getWarehouseId);
    }

    /** Total units of a product across the buildings that may be allocated from. */
    static int totalAvailable(List<StockLevel> levels, List<Warehouse> warehouses) {
        List<UUID> allowed = warehouses.stream().map(Warehouse::getId).toList();
        return levels.stream()
                .filter(level -> allowed.contains(level.getWarehouseId()))
                .mapToInt(StockLevel::getAvailableQuantity)
                .sum();
    }
}
