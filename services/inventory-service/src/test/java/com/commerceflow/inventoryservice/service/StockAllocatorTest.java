package com.commerceflow.inventoryservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.commerceflow.inventoryservice.entity.StockLevel;
import com.commerceflow.inventoryservice.entity.Warehouse;

/**
 * Choosing which building an order comes out of.
 *
 * <p>Pure arithmetic and preference, which is why it is worth pinning: none of it fails loudly.
 * A wrong choice here produces an order that ships from the far side of the continent, or in two
 * parcels when one would have done, and nothing anywhere reports a problem.
 */
class StockAllocatorTest {

    private static final UUID PRODUCT = UUID.randomUUID();

    private static final UUID AMS = UUID.randomUUID();
    private static final UUID MIL = UUID.randomUUID();
    private static final UUID LON = UUID.randomUUID();

    private static Warehouse warehouse(UUID id, String code, String country, int priority) {
        return Warehouse.builder()
                .id(id).code(code).name(code).countryCode(country)
                .priority(priority).active(true).build();
    }

    private static StockLevel level(UUID warehouseId, int available) {
        return StockLevel.builder()
                .id(UUID.randomUUID()).warehouseId(warehouseId).productId(PRODUCT)
                .availableQuantity(available).reservedQuantity(0).build();
    }

    private static final Warehouse AMSTERDAM = warehouse(AMS, "AMS", "NL", 10);
    private static final Warehouse MILAN = warehouse(MIL, "MIL", "IT", 20);
    private static final Warehouse LONDON = warehouse(LON, "LON", "GB", 5);

    private static List<StockAllocator.Allocation> allocate(Map<UUID, Integer> demand,
            List<StockLevel> levels, List<Warehouse> warehouses, String country) {
        return StockAllocator.allocate(demand, Map.of(PRODUCT, levels), warehouses, country);
    }

    @Test
    @DisplayName("one building that can fill the whole line wins, even over a closer one that cannot")
    void wholeLineFromOneBuilding() {
        // Milan is in the destination country and has two. Amsterdam has ten. Splitting would
        // mean two parcels and two tracking numbers for a customer who ordered one thing.
        List<StockAllocator.Allocation> result = allocate(
                Map.of(PRODUCT, 5),
                List.of(level(AMS, 10), level(MIL, 2)),
                List.of(AMSTERDAM, MILAN), "IT");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).warehouseId()).isEqualTo(AMS);
        assertThat(result.get(0).quantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("among buildings that can all fill it, the destination country wins")
    void destinationCountryIsPreferred() {
        // London has a better priority number. Milan avoids a customs form and a day.
        List<StockAllocator.Allocation> result = allocate(
                Map.of(PRODUCT, 2),
                List.of(level(LON, 10), level(MIL, 10)),
                List.of(LONDON, MILAN), "IT");

        assertThat(result.get(0).warehouseId()).isEqualTo(MIL);
    }

    @Test
    @DisplayName("with no local building, priority decides")
    void priorityDecidesAbroad() {
        List<StockAllocator.Allocation> result = allocate(
                Map.of(PRODUCT, 2),
                List.of(level(AMS, 10), level(LON, 10)),
                List.of(AMSTERDAM, LONDON), "FR");

        // London is priority 5, Amsterdam 10. Lower goes first.
        assertThat(result.get(0).warehouseId()).isEqualTo(LON);
    }

    @Test
    @DisplayName("splitting is the fallback, in the same preference order")
    void splitsWhenNoSingleBuildingCanCover() {
        List<StockAllocator.Allocation> result = allocate(
                Map.of(PRODUCT, 12),
                List.of(level(AMS, 8), level(MIL, 6)),
                List.of(AMSTERDAM, MILAN), "IT");

        // Better than refusing — the shop does have twelve — but second choice, and visible in
        // the reservation as two rows for one product.
        assertThat(result).hasSize(2);
        assertThat(result.get(0).warehouseId()).isEqualTo(MIL);
        assertThat(result.get(0).quantity()).isEqualTo(6);
        assertThat(result.get(1).quantity()).isEqualTo(6);
        assertThat(result.stream().mapToInt(StockAllocator.Allocation::quantity).sum())
                .isEqualTo(12);
    }

    @Test
    @DisplayName("a line that cannot be covered anywhere allocates nothing at all")
    void shortfallAllocatesNothing() {
        List<StockAllocator.Allocation> result = allocate(
                Map.of(PRODUCT, 20),
                List.of(level(AMS, 8), level(MIL, 6)),
                List.of(AMSTERDAM, MILAN), "NL");

        // Not fourteen of the twenty. A partly-filled line is an order the customer did not
        // place, and deciding to send most of it is not an allocator's decision to make.
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("an inactive building is not allocated from")
    void inactiveBuildingsAreSkipped() {
        // Passing only the active list is the caller's job; this pins that a level in a building
        // that is not on the list is ignored rather than silently used.
        List<StockAllocator.Allocation> result = allocate(
                Map.of(PRODUCT, 3),
                List.of(level(AMS, 10), level(MIL, 10)),
                List.of(MILAN), "NL");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).warehouseId()).isEqualTo(MIL);
    }

    @Test
    @DisplayName("an empty building is skipped rather than allocated zero")
    void emptyBuildingsAreSkipped() {
        List<StockAllocator.Allocation> result = allocate(
                Map.of(PRODUCT, 3),
                List.of(level(AMS, 0), level(MIL, 5)),
                List.of(AMSTERDAM, MILAN), "NL");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).warehouseId()).isEqualTo(MIL);
    }

    @Test
    @DisplayName("among equals, the fullest building goes first")
    void fullestWinsAmongEquals() {
        Warehouse a = warehouse(AMS, "AMS", "NL", 10);
        Warehouse b = warehouse(MIL, "MIL", "NL", 10);

        List<StockAllocator.Allocation> result = allocate(
                Map.of(PRODUCT, 20),
                List.of(level(AMS, 5), level(MIL, 15)),
                List.of(a, b), "NL");

        // Leaves the thinner building able to fill its own local orders.
        assertThat(result.get(0).warehouseId()).isEqualTo(MIL);
    }

    @Test
    @DisplayName("no destination country still allocates, by priority")
    void missingCountryFallsThrough() {
        // An allocation without a destination is less good, not wrong. Refusing to allocate
        // would turn a missing optional field into a failed checkout.
        List<StockAllocator.Allocation> result = allocate(
                Map.of(PRODUCT, 2),
                List.of(level(AMS, 10), level(LON, 10)),
                List.of(AMSTERDAM, LONDON), null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).warehouseId()).isEqualTo(LON);
    }

    @Test
    @DisplayName("the same inputs always produce the same allocation")
    void allocationIsReproducible() {
        Warehouse a = warehouse(AMS, "AMS", "NL", 10);
        Warehouse b = warehouse(MIL, "MIL", "NL", 10);
        List<StockLevel> levels = List.of(level(AMS, 5), level(MIL, 5));

        // Identical on every axis, so without a final tiebreak the answer would depend on
        // whatever order the database returned equal rows in — and an allocation that moves
        // between two runs is impossible to reason about when one of them goes wrong.
        UUID first = allocate(Map.of(PRODUCT, 3), levels, List.of(a, b), "NL").get(0).warehouseId();
        UUID again = allocate(Map.of(PRODUCT, 3), levels, List.of(b, a), "NL").get(0).warehouseId();

        assertThat(first).isEqualTo(again);
    }
}
