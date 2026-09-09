package com.commerceflow.inventoryservice.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.dto.PageResponse;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.inventoryservice.dto.CreateProductRequest;
import com.commerceflow.inventoryservice.dto.ProductResponse;
import com.commerceflow.inventoryservice.dto.UpdateProductRequest;
import com.commerceflow.inventoryservice.dto.UpdateStockRequest;
import com.commerceflow.inventoryservice.entity.Category;
import com.commerceflow.inventoryservice.entity.InventoryItem;
import com.commerceflow.inventoryservice.entity.Product;
import com.commerceflow.inventoryservice.mapper.ProductMapper;
import com.commerceflow.inventoryservice.repository.CategoryRepository;
import com.commerceflow.inventoryservice.repository.InventoryItemRepository;
import com.commerceflow.inventoryservice.repository.ProductImageRepository;
import com.commerceflow.inventoryservice.repository.ProductRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Catalogue read and back-office write operations.
 *
 * <p>Single product reads are cached in Redis; searches are not, because their result set changes
 * with every stock movement and the hit rate would not justify the invalidation complexity.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    public static final String CACHE_BY_ID = "products";
    public static final String CACHE_BY_SKU = "productsBySku";

    private static final List<String> SORTABLE_FIELDS =
            List.of("name", "price", "createdAt", "sku");

    private final ProductRepository productRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final CategoryRepository categoryRepository;
    private final ProductImageRepository productImageRepository;
    private final CategoryService categoryService;
    private final WarehouseService warehouseService;
    private final ProductMapper productMapper;
    private final AuditService auditService;

    /**
     * Paged catalogue search; every filter is optional.
     *
     * <h2>Two different queries, chosen by whether there is a search term</h2>
     *
     * <p>With a term, this runs Postgres full-text search and orders by relevance. Without one, it
     * runs an ordinary filtered query with whatever sort the caller asked for — because "most
     * relevant" is meaningless when nothing was searched for, and ranking an unfiltered catalogue
     * would return it in an order nobody chose.
     *
     * <p>The previous implementation was {@code LIKE '%term%'} on name and SKU. Three things were
     * wrong with it, in increasing order of importance: a leading wildcard cannot use an index, so
     * every keystroke was a full table scan; it matched substrings rather than words, so "art"
     * matched "cartridge"; and it had no notion of a word's root, so a customer searching
     * "headphones" found nothing filed as "headphone".
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> search(String category, String search, boolean activeOnly,
                                                int page, int size, String sortBy, String direction) {
        String term = blankToNull(search);
        String categorySlug = blankToNull(category);

        Page<Product> products;
        if (term == null) {
            Pageable pageable = PageRequest.of(page, size, sort(sortBy, direction));
            products = productRepository.filter(categorySlug, activeOnly, pageable);
        } else {
            // Relevance order comes from the query itself, so no Sort is passed -- adding one
            // would silently override the ranking and produce results that look arbitrary.
            products = productRepository.fullTextSearch(categorySlug, toTsQuery(term), activeOnly,
                    PageRequest.of(page, size));
        }

        return PageResponse.of(project(products.getContent()), page, size,
                products.getTotalElements());
    }

    /**
     * Turns what a customer typed into a {@code tsquery}.
     *
     * <p>Words are ANDed, and the last one gets a {@code :*} prefix so that search-as-you-type
     * finds "lap" while the customer is still typing "laptop". Everything that is not a letter or
     * a digit is dropped rather than escaped: {@code to_tsquery} has its own operator syntax, and
     * passing user input into it unfiltered turns a stray {@code &} or {@code !} into a syntax
     * error thrown at somebody who was only trying to search.
     *
     * <p><b>No typo tolerance.</b> "labtop" finds nothing. Fixing that means trigram similarity
     * and a second index, which is a real feature rather than a tweak — it is left undone and
     * stated, rather than half-done.
     */
    static String toTsQuery(String raw) {
        String[] words = raw.trim().toLowerCase(java.util.Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}]+");
        List<String> terms = new java.util.ArrayList<>();
        for (String word : words) {
            if (!word.isBlank()) {
                terms.add(word);
            }
        }
        if (terms.isEmpty()) {
            return null;
        }
        // Only the final word is a prefix. Making them all prefixes would match far too much:
        // "a b" would find everything beginning with a and everything beginning with b.
        terms.set(terms.size() - 1, terms.get(terms.size() - 1) + ":*");
        return String.join(" & ", terms);
    }

    /**
     * Attaches stock and category to a page of products.
     *
     * <p>Two queries for the whole page rather than two per row. Fifty products would otherwise be
     * a hundred round trips, most of them fetching the same handful of category rows.
     */
    private List<ProductResponse> project(List<Product> products) {
        Map<UUID, InventoryItem> stock = stockFor(products);
        Map<UUID, Category> categories = categoriesFor(products);
        Map<UUID, String> tiles = tileImagesFor(products);
        return products.stream()
                .map(product -> productMapper.toResponse(product, stock.get(product.getId()),
                        categories.get(product.getCategoryId()), tiles.get(product.getId())))
                .toList();
    }

    /**
     * The tile image of each product that has uploads, in one query.
     *
     * <p>Only the first image per product survives, and the query already returns them in gallery
     * order — so {@code putIfAbsent} keeps position 0 without a sort or a second query.
     */
    private Map<UUID, String> tileImagesFor(List<Product> products) {
        List<UUID> ids = products.stream().map(Product::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> tiles = new HashMap<>();
        productImageRepository.findByProductIdInOrderByPositionAscCreatedAtAsc(ids)
                .forEach(image -> tiles.putIfAbsent(image.getProductId(),
                        ProductImageService.url(image.getProductId(), image.getId())));
        return tiles;
    }

    /** The tile image of a single product, or {@code null} when it has no uploads. */
    private String tileImageOf(UUID productId) {
        return productImageRepository
                .findFirstByProductIdOrderByPositionAscCreatedAtAsc(productId)
                .map(image -> ProductImageService.url(productId, image.getId()))
                .orElse(null);
    }

    private Map<UUID, Category> categoriesFor(List<Product> products) {
        List<UUID> ids = products.stream()
                .map(Product::getCategoryId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Category> byId = new HashMap<>();
        categoryRepository.findAllById(ids).forEach(category -> byId.put(category.getId(), category));
        return byId;
    }

    /** The category a product is filed under, or {@code null} when it is unfiled. */
    private Category categoryOf(Product product) {
        return product.getCategoryId() == null
                ? null
                : categoryRepository.findById(product.getCategoryId()).orElse(null);
    }

    /** @throws ResourceNotFoundException when no such product exists */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CACHE_BY_ID, key = "#productId")
    public ProductResponse getById(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + productId));
        return productMapper.toResponse(product,
                inventoryItemRepository.findById(productId).orElse(null), categoryOf(product),
                tileImageOf(productId));
    }

    /** @throws ResourceNotFoundException when no such product exists */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CACHE_BY_SKU, key = "#sku.toUpperCase()")
    public ProductResponse getBySku(String sku) {
        Product product = productRepository.findBySkuIgnoreCase(sku)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.PRODUCT_NOT_FOUND, "Product not found for SKU " + sku));
        return productMapper.toResponse(product,
                inventoryItemRepository.findById(product.getId()).orElse(null), categoryOf(product),
                tileImageOf(product.getId()));
    }

    /**
     * Batch lookup by id, used by Order Service to price a basket in a single call.
     *
     * <p>Unknown ids are simply absent from the result; it is the caller who decides whether a
     * missing product is an error, and Inventory re-checks everything anyway when it reserves.
     */
    @Transactional(readOnly = true)
    public List<ProductResponse> findAllById(List<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        return project(productRepository.findByIdIn(productIds));
    }

    /**
     * Adds a product together with its opening stock.
     *
     * @throws ConflictException when the SKU is already taken
     */
    @Transactional
    @CacheEvict(cacheNames = {CACHE_BY_ID, CACHE_BY_SKU}, allEntries = true)
    public ProductResponse create(CreateProductRequest request) {
        if (productRepository.existsBySkuIgnoreCase(request.sku())) {
            throw new ConflictException(ErrorCode.SKU_ALREADY_EXISTS,
                    "SKU " + request.sku() + " already exists");
        }

        UUID productId = UUID.randomUUID();
        Product product = productRepository.save(Product.builder()
                .id(productId)
                .sku(request.sku())
                .name(request.name())
                .description(request.description())
                .categoryId(resolveCategory(request.categoryId(), request.categorySlug()))
                .price(request.price())
                .currency(request.currencyOrDefault())
                .imageUrl(request.imageUrl())
                .active(true)
                .build());

        // Opens the stock row in a real building as well as the summary. Without this a new
        // product would have an opening quantity that exists only in the summary and can never
        // be allocated from anywhere -- it would read as in stock and refuse every order.
        warehouseService.openStock(productId, request.initialQuantity(),
                request.reorderLevelOrDefault());

        InventoryItem item = inventoryItemRepository.save(InventoryItem.builder()
                .productId(productId)
                .sku(product.getSku())
                .availableQuantity(request.initialQuantity())
                .reservedQuantity(0)
                .reorderLevel(request.reorderLevelOrDefault())
                .updatedAt(Instant.now())
                .build());

        log.info("Created product {} ({}) with {} units", productId, product.getSku(),
                item.getAvailableQuantity());
        return productMapper.toResponse(product, item, categoryOf(product));
    }

    /**
     * Replaces the editable fields of a product.
     *
     * <p>SKU and stock are deliberately not editable here — see {@link UpdateProductRequest} for
     * why. Everything else is a straight replacement, including {@code active}.
     *
     * <p><b>Changing the price does not change any existing order.</b> Order lines snapshot the
     * name and price they were bought at, because an order is a historical record of what a
     * customer agreed to pay. A correction here affects what the next customer sees and nothing
     * that has already happened.
     *
     * @throws ResourceNotFoundException when the product does not exist
     */
    @Transactional
    @CacheEvict(cacheNames = {CACHE_BY_ID, CACHE_BY_SKU}, allEntries = true)
    public ProductResponse update(UUID productId, UpdateProductRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + productId));

        boolean wasActive = product.isActive();

        product.setName(request.name().trim());
        product.setDescription(request.description());
        product.setCategoryId(resolveCategory(request.categoryId(), request.categorySlug()));
        product.setPrice(request.price());
        product.setImageUrl(request.imageUrl());
        product.setActive(Boolean.TRUE.equals(request.active()));
        product.setUpdatedAt(Instant.now());

        if (wasActive != product.isActive()) {
            // Worth its own line in the log: taking a product off sale is the closest thing this
            // API has to a delete, and the question "when did this stop being for sale" is one
            // somebody eventually asks.
            log.info("Product {} ({}) is now {}", productId, product.getSku(),
                    product.isActive() ? "on sale" : "off sale");
        }

        // Price and availability, which is what a customer sees and pays. The summary names
        // the change without quoting the values -- see AuditEntry on why a diff would turn
        // this table into a copy of the catalogue.
        auditService.record("PRODUCT_UPDATED", "PRODUCT", productId,
                "Updated " + product.getSku()
                        + (wasActive == product.isActive() ? ""
                                : product.isActive() ? "; put on sale" : "; taken off sale"));

        log.info("Updated product {} ({})", productId, product.getSku());
        return productMapper.toResponse(product,
                inventoryItemRepository.findById(productId).orElse(null), categoryOf(product));
    }

    /**
     * Resolves a category from an id or a slug.
     *
     * <p>An unknown one is an error rather than a silent {@code null}. Filing a product nowhere
     * because somebody mistyped a slug is how a product disappears from the section it was meant
     * to be in, with nothing anywhere reporting a problem.
     */
    private UUID resolveCategory(UUID categoryId, String slug) {
        if (categoryId != null) {
            return categoryService.require(categoryId).getId();
        }
        String trimmed = blankToNull(slug);
        return trimmed == null ? null : categoryService.require(trimmed).getId();
    }

    /**
     * Back-office stock correction.
     *
     * <p>Never touches {@code reservedQuantity}: stock that a running saga is already holding is
     * not the back office to move, and doing so would break the compensation arithmetic.
     *
     * @throws ResourceNotFoundException when the product or its stock row is missing
     */
    @Transactional
    @CacheEvict(cacheNames = {CACHE_BY_ID, CACHE_BY_SKU}, allEntries = true)
    public ProductResponse updateStock(UUID productId, UpdateStockRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + productId));

        InventoryItem item = inventoryItemRepository.lockAllByProductIds(List.of(productId))
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.PRODUCT_NOT_FOUND, "No stock record for product " + productId));

        int target = switch (request.operationOrDefault()) {
            case SET -> request.quantity();
            case INCREASE -> item.getAvailableQuantity() + request.quantity();
            case DECREASE -> item.getAvailableQuantity() - request.quantity();
        };
        item.setAvailable(Math.max(target, 0));

        log.info("Stock for {} set to {} via {} ({})", product.getSku(), item.getAvailableQuantity(),
                request.operationOrDefault(), request.reason());
        return productMapper.toResponse(product, item);
    }

    private Map<UUID, InventoryItem> stockFor(List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = products.stream().map(Product::getId).toList();
        Map<UUID, InventoryItem> byProduct = new HashMap<>();
        inventoryItemRepository.findByProductIdIn(ids)
                .forEach(item -> byProduct.put(item.getProductId(), item));
        return byProduct;
    }

    private static Sort sort(String sortBy, String direction) {
        String field = SORTABLE_FIELDS.contains(sortBy) ? sortBy : "name";
        Sort.Direction dir =
                "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return Sort.by(dir, field);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
