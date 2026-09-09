package com.commerceflow.inventoryservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.inventoryservice.dto.CreateProductRequest;
import com.commerceflow.inventoryservice.dto.ProductResponse;
import com.commerceflow.inventoryservice.dto.UpdateStockRequest;
import com.commerceflow.inventoryservice.entity.InventoryItem;
import com.commerceflow.inventoryservice.entity.Product;
import com.commerceflow.inventoryservice.mapper.ProductMapper;
import com.commerceflow.inventoryservice.entity.Category;
import com.commerceflow.inventoryservice.repository.CategoryRepository;
import com.commerceflow.inventoryservice.repository.InventoryItemRepository;
import com.commerceflow.inventoryservice.repository.ProductImageRepository;
import com.commerceflow.inventoryservice.repository.ProductRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductServiceTest {

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID CATEGORY_ID = UUID.randomUUID();

    @Mock
    private ProductRepository productRepository;

    @Mock
    private InventoryItemRepository inventoryItemRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private ProductImageRepository productImageRepository;

    @Mock
    private WarehouseService warehouseService;

    @Mock
    private CategoryService categoryService;

    @Mock
    private AuditService auditService;

    private ProductService productService;
    private Product product;
    private InventoryItem stockItem;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository, inventoryItemRepository,
                categoryRepository, productImageRepository, categoryService, warehouseService,
                new ProductMapper(), auditService);

        product = Product.builder()
                .id(PRODUCT_ID)
                .sku("CF-LAPTOP-001")
                .name("CommerceFlow Developer Laptop 14")
                .categoryId(CATEGORY_ID)
                .price(new BigDecimal("1899.00"))
                .currency("EUR")
                .active(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        stockItem = InventoryItem.builder()
                .productId(PRODUCT_ID)
                .sku(product.getSku())
                .availableQuantity(10)
                .reservedQuantity(4)
                .reorderLevel(2)
                .updatedAt(Instant.now())
                .build();

        when(productRepository.save(any(Product.class))).thenAnswer(call -> call.getArgument(0));
        when(inventoryItemRepository.save(any(InventoryItem.class)))
                .thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("a product is returned together with its stock position")
    void readsProductWithStock() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(inventoryItemRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(stockItem));

        ProductResponse response = productService.getById(PRODUCT_ID);

        assertThat(response.sku()).isEqualTo("CF-LAPTOP-001");
        assertThat(response.availableQuantity()).isEqualTo(10);
        assertThat(response.reservedQuantity()).isEqualTo(4);
        assertThat(response.inStock()).isTrue();
    }

    @Test
    @DisplayName("an unknown product id is a 404, not an empty response")
    void unknownProductIsNotFound() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getById(PRODUCT_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .extracting(ex -> ((ResourceNotFoundException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("creating a product also opens its stock ledger row")
    void createOpensStockRow() {
        when(productRepository.existsBySkuIgnoreCase("CF-NEW-001")).thenReturn(false);
        // An unknown slug is an error rather than a silent null, so the stub has to exist —
        // filing a product nowhere because somebody mistyped is how it vanishes from its section.
        when(categoryService.require("accessories")).thenReturn(
                Category.builder().id(CATEGORY_ID).slug("accessories").name("Accessories").build());

        ProductResponse response = productService.create(new CreateProductRequest(
                "CF-NEW-001", "New product", null, null, "accessories",
                new BigDecimal("19.99"), null, null, 25, 5));

        assertThat(response.currency()).isEqualTo("EUR");
        assertThat(response.availableQuantity()).isEqualTo(25);
        verify(inventoryItemRepository).save(any(InventoryItem.class));
    }

    @Test
    @DisplayName("a duplicate SKU is rejected before anything is written")
    void duplicateSkuIsRejected() {
        when(productRepository.existsBySkuIgnoreCase("CF-LAPTOP-001")).thenReturn(true);

        assertThatThrownBy(() -> productService.create(new CreateProductRequest(
                "CF-LAPTOP-001", "Duplicate", null, null, null, BigDecimal.ONE, "EUR", null, 1,
                0)))
                .isInstanceOf(ConflictException.class);

        verify(productRepository, never()).save(any(Product.class));
        verify(inventoryItemRepository, never()).save(any(InventoryItem.class));
    }

    @Test
    @DisplayName("a stock correction never touches units a running saga is holding")
    void stockCorrectionLeavesReservationsAlone() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(inventoryItemRepository.lockAllByProductIds(anyList())).thenReturn(List.of(stockItem));

        ProductResponse response = productService.updateStock(PRODUCT_ID,
                new UpdateStockRequest(100, UpdateStockRequest.StockOperation.SET, "stock count"));

        assertThat(response.availableQuantity()).isEqualTo(100);
        assertThat(response.reservedQuantity()).isEqualTo(4);
    }

    @Test
    @DisplayName("INCREASE and DECREASE are applied relative to the current level and clamp at zero")
    void relativeStockCorrections() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(inventoryItemRepository.lockAllByProductIds(anyList())).thenReturn(List.of(stockItem));

        assertThat(productService.updateStock(PRODUCT_ID, new UpdateStockRequest(
                5, UpdateStockRequest.StockOperation.INCREASE, null)).availableQuantity())
                .isEqualTo(15);

        assertThat(productService.updateStock(PRODUCT_ID, new UpdateStockRequest(
                999, UpdateStockRequest.StockOperation.DECREASE, null)).availableQuantity())
                .isZero();
    }
}
