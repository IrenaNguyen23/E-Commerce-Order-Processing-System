package com.commerceflow.inventoryservice.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.inventoryservice.entity.Category;
import com.commerceflow.inventoryservice.entity.InventoryItem;
import com.commerceflow.inventoryservice.entity.StockLevel;
import com.commerceflow.inventoryservice.entity.Warehouse;
import com.commerceflow.inventoryservice.entity.Product;
import com.commerceflow.inventoryservice.repository.CategoryRepository;
import com.commerceflow.inventoryservice.repository.InventoryItemRepository;
import com.commerceflow.inventoryservice.repository.StockLevelRepository;
import com.commerceflow.inventoryservice.repository.WarehouseRepository;
import com.commerceflow.inventoryservice.repository.ProductRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Fills the catalogue out far enough to exercise the storefront.
 *
 * <p>{@code V2__seed_catalogue.sql} seeds the seven products the saga tests and the README
 * walkthrough address by id. Seven is enough to prove the flow works and not enough to try the
 * UI on: no second page at twelve per page, two thin categories, nothing sold out, nothing
 * deactivated, and a price range too narrow for sorting to mean anything.
 *
 * <p>This adds twelve more, chosen so that every list control has something to bite on:
 *
 * <ul>
 *   <li><b>Nineteen products</b> — a second page at the storefront's twelve per page
 *   <li><b>Six categories</b> — the category nav is derived from the catalogue, so it needs breadth
 *   <li><b>€29 to €2,499</b> — sort by price is visibly doing something
 *   <li><b>One sold out</b> ({@code CF-SPEAKER-001}, 0 units) — the sold-out badge and the
 *       add-to-basket guard
 *   <li><b>One below its reorder level</b> ({@code CF-BAND-001}, 3 of 10) — the low-stock warning
 *       in admin inventory
 *   <li><b>One deactivated</b> ({@code CF-PROTO-001}) — visible in admin, absent from the
 *       storefront, and rejected by {@code POST /api/orders}
 * </ul>
 *
 * <h2>Why this is not a Flyway migration</h2>
 *
 * <p>Because demo data has no business being in a production catalogue, and a migration runs
 * everywhere. This follows the pattern Auth Service already uses for its bootstrap account:
 * an {@code ApplicationRunner} behind a property that only {@code docker-compose.yml} turns on.
 * It also stays out of the schema-history table, so it can be changed later without the
 * out-of-order headache a demo-only migration version would create.
 *
 * <p>Idempotent: a product already present by SKU is left alone, so a restart is a no-op and an
 * operator's edits through the admin console are never reverted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "commerceflow.bootstrap.demo-catalogue", name = "enabled",
        havingValue = "true")
public class DemoCatalogueInitializer implements ApplicationRunner {

    private static final String CURRENCY = "EUR";

    private final ProductRepository productRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final CategoryRepository categoryRepository;
    private final StockLevelRepository stockLevelRepository;
    private final WarehouseRepository warehouseRepository;

    /**
     * One demo product and its stock line.
     *
     * @param quantity     units available
     * @param reorderLevel the point below which admin inventory flags it as low
     * @param swatch       background of the placeholder image, as a hex triplet
     * @param label        one or two characters drawn on the placeholder image
     */
    private record DemoProduct(String id, String sku, String name, String description,
                               String category, String price, int quantity, int reorderLevel,
                               boolean active, String swatch, String label) {
    }

    private static final List<DemoProduct> PRODUCTS = List.of(
            new DemoProduct("11111111-1111-1111-1111-111111111108", "CF-LAPTOP-002",
                    "CommerceFlow Developer Laptop 16",
                    "16 inch, 64 GB RAM, 2 TB NVMe. The one you buy when compile time is money.",
                    "COMPUTERS", "2499.00", 18, 5, true, "1e293b", "L16"),

            new DemoProduct("11111111-1111-1111-1111-111111111109", "CF-TABLET-001",
                    "CommerceFlow Tablet 11",
                    "11 inch, 128 GB, pen support. Reads a spec on the train.",
                    "COMPUTERS", "649.00", 40, 10, true, "334155", "TB"),

            new DemoProduct("11111111-1111-1111-1111-111111111110", "CF-PHONE-002",
                    "CommerceFlow Phone X Mini",
                    "5.4 inch OLED, 128 GB. The same phone, one hand.",
                    "PHONES", "749.00", 65, 15, true, "0f766e", "PX"),

            new DemoProduct("11111111-1111-1111-1111-111111111111", "CF-EARBUDS-001",
                    "CommerceFlow Wireless Earbuds",
                    "In-ear, 8 hour battery, wireless charging case.",
                    "AUDIO", "129.00", 150, 30, true, "7c2d12", "EB"),

            // Zero stock on purpose: the storefront must show it and refuse to sell it.
            new DemoProduct("11111111-1111-1111-1111-111111111112", "CF-SPEAKER-001",
                    "CommerceFlow Desk Speaker",
                    "Compact stereo pair, USB-C. Currently sold out.",
                    "AUDIO", "199.00", 0, 10, true, "9a3412", "SP"),

            new DemoProduct("11111111-1111-1111-1111-111111111113", "CF-WATCH-001",
                    "CommerceFlow Smart Watch 2",
                    "42 mm, GPS, 5 day battery.",
                    "WEARABLES", "329.00", 45, 10, true, "4c1d95", "WA"),

            // Below its reorder level on purpose: admin inventory should flag it.
            new DemoProduct("11111111-1111-1111-1111-111111111114", "CF-BAND-001",
                    "CommerceFlow Fitness Band",
                    "Heart rate, sleep tracking, 14 day battery. Running low.",
                    "WEARABLES", "59.00", 3, 10, true, "6d28d9", "FB"),

            new DemoProduct("11111111-1111-1111-1111-111111111115", "CF-HUB-001",
                    "CommerceFlow Smart Home Hub",
                    "Thread and Matter, local control, no cloud account required.",
                    "HOME", "119.00", 30, 8, true, "115e59", "HB"),

            new DemoProduct("11111111-1111-1111-1111-111111111116", "CF-BULB-001",
                    "CommerceFlow Smart Bulb, 4 pack",
                    "Tunable white, E27. Four in a box.",
                    "HOME", "49.00", 200, 40, true, "0e7490", "BL"),

            new DemoProduct("11111111-1111-1111-1111-111111111117", "CF-CABLE-001",
                    "CommerceFlow USB-C Cable, 2 m",
                    "240 W, braided. The cheapest thing in the catalogue.",
                    "ACCESSORIES", "29.00", 300, 50, true, "374151", "CB"),

            new DemoProduct("11111111-1111-1111-1111-111111111118", "CF-STAND-001",
                    "CommerceFlow Aluminium Laptop Stand",
                    "Folds flat, raises the screen to eye level.",
                    "ACCESSORIES", "89.00", 25, 8, true, "475569", "ST"),

            // Deactivated on purpose: admin sees it, the storefront does not, and an order for it
            // is rejected with "no longer for sale".
            new DemoProduct("11111111-1111-1111-1111-111111111119", "CF-PROTO-001",
                    "CommerceFlow Prototype Messenger Bag",
                    "Never went to production. Kept in the catalogue, deactivated.",
                    "ACCESSORIES", "249.00", 5, 0, false, "78350f", "PB"));

    /** The seven products from {@code V2}, so the whole grid has artwork rather than most of it. */
    private static final List<String[]> V2_SWATCHES = List.of(
            new String[] {"CF-LAPTOP-001", "1e293b", "L14"},
            new String[] {"CF-PHONE-001", "0f766e", "PH"},
            new String[] {"CF-HEADSET-001", "7c2d12", "HS"},
            new String[] {"CF-KEYBOARD-001", "374151", "KB"},
            new String[] {"CF-MOUSE-001", "475569", "MS"},
            new String[] {"CF-MONITOR-001", "334155", "MN"},
            new String[] {"CF-LIMITED-001", "b45309", "LT"});

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int created = 0;
        for (DemoProduct demo : PRODUCTS) {
            if (productRepository.findBySkuIgnoreCase(demo.sku()).isPresent()) {
                continue;
            }
            insert(demo);
            created++;
        }

        int illustrated = 0;
        for (String[] swatch : V2_SWATCHES) {
            illustrated += illustrate(swatch[0], swatch[1], swatch[2]);
        }

        if (created == 0 && illustrated == 0) {
            log.info("Demo catalogue already present, nothing to seed");
        } else {
            log.info("Demo catalogue seeded: {} product(s) added, {} illustrated", created,
                    illustrated);
        }
    }

    /**
     * The section a demo product belongs in, created on first use.
     *
     * <p>Created here rather than assumed to exist, so seeding works on an empty database and on
     * one where an operator has already added sections of their own.
     */
    private UUID categoryId(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String slug = Category.slugify(name);
        return categoryRepository.findBySlugIgnoreCase(slug)
                .orElseGet(() -> {
                    Instant now = Instant.now();
                    return categoryRepository.save(Category.builder()
                            .id(UUID.randomUUID())
                            .slug(slug)
                            // "COMPUTERS" reads as a database dump; "Computers" reads as a shop.
                            .name(name.charAt(0) + name.substring(1).toLowerCase(java.util.Locale.ROOT))
                            .position(0)
                            .active(true)
                            .createdAt(now)
                            .updatedAt(now)
                            .build());
                })
                .getId();
    }

    /**
     * The id of a demo warehouse, created on first use.
     *
     * <p>Two of them, so a fresh install can exercise the split-across-buildings allocation path
     * without an operator having to open a second warehouse first. A feature that only ever runs
     * in production is a feature nobody has seen work.
     */
    private UUID warehouseId(String code, String name, String country, int priority) {
        return warehouseRepository.findByCodeIgnoreCase(code)
                .orElseGet(() -> {
                    Instant now = Instant.now();
                    return warehouseRepository.save(Warehouse.builder()
                            .id(UUID.randomUUID())
                            .code(code)
                            .name(name)
                            .countryCode(country)
                            .priority(priority)
                            .active(true)
                            .createdAt(now)
                            .updatedAt(now)
                            .build());
                })
                .getId();
    }

    private void insert(DemoProduct demo) {
        Instant now = Instant.now();
        UUID id = UUID.fromString(demo.id());

        productRepository.save(Product.builder()
                .id(id)
                .sku(demo.sku())
                .name(demo.name())
                .description(demo.description())
                .categoryId(categoryId(demo.category()))
                .price(new BigDecimal(demo.price()))
                .currency(CURRENCY)
                .imageUrl(placeholderImage(demo.swatch(), demo.label()))
                .active(demo.active())
                .createdAt(now)
                .updatedAt(now)
                .build());

        // Stock goes into a building, and the summary is derived from it. Two buildings, so
        // that a fresh install can exercise the split-across-warehouses path without an
        // operator having to set one up first.
        int inAmsterdam = demo.quantity();
        int inMilan = Math.max(1, demo.quantity() / 3);

        stockLevelRepository.save(StockLevel.builder()
                .id(UUID.randomUUID())
                .warehouseId(warehouseId("AMS", "Amsterdam warehouse", "NL", 10))
                .productId(id)
                .availableQuantity(inAmsterdam)
                .reservedQuantity(0)
                .reorderLevel(demo.reorderLevel())
                .updatedAt(now)
                .build());

        stockLevelRepository.save(StockLevel.builder()
                .id(UUID.randomUUID())
                .warehouseId(warehouseId("MIL", "Milan warehouse", "IT", 20))
                .productId(id)
                .availableQuantity(inMilan)
                .reservedQuantity(0)
                .reorderLevel(demo.reorderLevel())
                .updatedAt(now)
                .build());

        inventoryItemRepository.save(InventoryItem.builder()
                .productId(id)
                .sku(demo.sku())
                .availableQuantity(inAmsterdam + inMilan)
                .reservedQuantity(0)
                .reorderLevel(demo.reorderLevel())
                .updatedAt(now)
                .build());
    }

    /** Adds artwork to a product that has none. Never overwrites a real image. */
    private int illustrate(String sku, String swatch, String label) {
        return productRepository.findBySkuIgnoreCase(sku)
                .filter(product -> product.getImageUrl() == null)
                .map(product -> {
                    product.setImageUrl(placeholderImage(swatch, label));
                    product.setUpdatedAt(Instant.now());
                    return 1;
                })
                .orElse(0);
    }

    /**
     * An inline SVG placeholder, as a {@code data:} URI.
     *
     * <p>Self-contained on purpose. Pointing at a placeholder image service would make the
     * storefront depend on the internet being reachable — which, behind a corporate proxy, is
     * exactly the environment where a demo is most likely to be run and least likely to work.
     *
     * <p>Attribute quotes are percent-encoded rather than written literally: a raw quote in a URI
     * is invalid even though browsers tolerate it, and it would need SQL escaping if this ever
     * moved into a migration.
     *
     * <p>Kept well under the 500-character {@code image_url} column;
     * {@code DemoCatalogueInitializerTest} fails the build if that ever stops being true.
     */
    static String placeholderImage(String swatch, String label) {
        return "data:image/svg+xml,"
                + "%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20viewBox=%220%200%20300%20300%22%3E"
                + "%3Crect%20width=%22300%22%20height=%22300%22%20fill=%22%23" + swatch + "%22/%3E"
                + "%3Ctext%20x=%22150%22%20y=%22186%22%20font-family=%22sans-serif%22"
                + "%20font-size=%2288%22%20font-weight=%22700%22%20text-anchor=%22middle%22"
                + "%20fill=%22%23f8fafc%22%3E" + label + "%3C/text%3E%3C/svg%3E";
    }

    /** Exposed for the test that guards the column length. */
    static List<String> allImageUrls() {
        return java.util.stream.Stream.concat(
                        PRODUCTS.stream().map(p -> placeholderImage(p.swatch(), p.label())),
                        V2_SWATCHES.stream().map(s -> placeholderImage(s[1], s[2])))
                .toList();
    }
}
