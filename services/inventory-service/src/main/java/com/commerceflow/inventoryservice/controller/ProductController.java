package com.commerceflow.inventoryservice.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.commerceflow.common.dto.ApiResponse;
import com.commerceflow.common.dto.PageResponse;
import com.commerceflow.inventoryservice.dto.CreateProductRequest;
import com.commerceflow.inventoryservice.dto.ProductLookupRequest;
import com.commerceflow.inventoryservice.dto.ProductResponse;
import com.commerceflow.inventoryservice.dto.UpdateProductRequest;
import com.commerceflow.inventoryservice.dto.UpdateStockRequest;
import com.commerceflow.inventoryservice.service.ProductService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** Catalogue API, per {@code contracts/apis.md}. */
@Validated
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Tag(name = "Catalogue", description = "Products and stock levels")
public class ProductController {

    private final ProductService productService;

    @GetMapping
    @Operation(summary = "Search the catalogue")
    public ResponseEntity<ApiResponse<PageResponse<ProductResponse>>> list(
            @Parameter(description = "Exact category match") @RequestParam(required = false) String category,
            @Parameter(description = "Free text over name and SKU") @RequestParam(required = false) String search,
            @Parameter(description = "Hide deactivated products") @RequestParam(defaultValue = "true") boolean activeOnly,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name") String sortBy,
            @RequestParam(defaultValue = "asc") String direction) {

        PageResponse<ProductResponse> result =
                productService.search(category, search, activeOnly, page, size, sortBy, direction);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one product by id")
    public ResponseEntity<ApiResponse<ProductResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(productService.getById(id)));
    }

    @GetMapping("/sku/{sku}")
    @Operation(summary = "Fetch one product by SKU")
    public ResponseEntity<ApiResponse<ProductResponse>> getBySku(@PathVariable String sku) {
        return ResponseEntity.ok(ApiResponse.ok(productService.getBySku(sku)));
    }

    @PostMapping("/lookup")
    @Operation(summary = "Batch lookup by id",
            description = "Used by Order Service to price a whole basket in one call. Unknown ids "
                    + "are omitted from the result rather than failing the request.")
    public ResponseEntity<ApiResponse<List<ProductResponse>>> lookup(
            @Valid @RequestBody ProductLookupRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(productService.findAllById(request.productIds())));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Add a product together with its opening stock")
    public ResponseEntity<ApiResponse<ProductResponse>> create(
            @Valid @RequestBody CreateProductRequest request) {
        ProductResponse created = productService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created, "Product created"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Update a product",
            description = "Back office only. SKU and stock are not editable here: the SKU is how "
                    + "everything outside this service refers to the product, and stock has its "
                    + "own endpoint with its own rules. Set `active` to false to take a product "
                    + "off sale — there is no delete, because orders reference products and a "
                    + "customer has a receipt for what they bought.")
    public ResponseEntity<ApiResponse<ProductResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProductRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(productService.update(id, request),
                "Product updated"));
    }

    @PutMapping("/{id}/stock")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Correct the available stock of a product",
            description = "Only the available quantity is affected; units already reserved by a "
                    + "running order saga are never touched.")
    public ResponseEntity<ApiResponse<ProductResponse>> updateStock(
            @PathVariable UUID id, @Valid @RequestBody UpdateStockRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok(productService.updateStock(id, request), "Stock updated"));
    }
}
