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
import com.commerceflow.inventoryservice.dto.StockLevelResponse;
import com.commerceflow.inventoryservice.dto.WarehouseRequest;
import com.commerceflow.inventoryservice.dto.WarehouseResponse;
import com.commerceflow.inventoryservice.service.WarehouseService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * Warehouses and what is in them.
 *
 * <p>Entirely administrator-only, including the reads: where a shop keeps its stock and how much
 * of it there is are commercially interesting facts that a storefront has no need for. A customer
 * is told whether something is available, not where it is.
 *
 * <p>There is no delete. See {@link WarehouseService} for why closing a building and removing its
 * row are different operations, only one of which anybody actually wants.
 */
@Validated
@RestController
@RequestMapping("/api/warehouses")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Warehouses", description = "Where stock physically is")
public class WarehouseController {

    private final WarehouseService warehouseService;

    @GetMapping
    @Operation(summary = "List warehouses", description = "Best allocation priority first.")
    public ResponseEntity<ApiResponse<List<WarehouseResponse>>> list(
            @RequestParam(defaultValue = "false") boolean includeInactive) {

        return ResponseEntity.ok(ApiResponse.ok(warehouseService.list(!includeInactive)));
    }

    @PostMapping
    @Operation(summary = "Open a warehouse")
    public ResponseEntity<ApiResponse<WarehouseResponse>> create(
            @Valid @RequestBody WarehouseRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(warehouseService.create(request), "Warehouse opened"));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a warehouse",
            description = """
                    Everything but the code, which is immutable once the warehouse exists.

                    Setting `active` to false closes it to **new** orders and leaves existing \
                    reservations alone — which is what somebody closing a building wants during \
                    the weeks it takes, rather than abandoning what it already promised to fill.""")
    public ResponseEntity<ApiResponse<WarehouseResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody WarehouseRequest request) {

        return ResponseEntity.ok(
                ApiResponse.ok(warehouseService.update(id, request), "Warehouse updated"));
    }

    @GetMapping("/{id}/stock")
    @Operation(summary = "What is in one warehouse")
    public ResponseEntity<ApiResponse<List<StockLevelResponse>>> contents(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(warehouseService.contents(id)));
    }

    @PutMapping("/{id}/stock/{productId}")
    @Operation(summary = "Set how many units of a product are in this warehouse",
            description = """
                    Creates the row if this building has never carried the product.

                    **Held units are never touched.** Stock reserved by a running saga is not the \
                    back office's to move: taking it would break the compensation arithmetic, and \
                    a later release would put back stock that had already been counted away.""")
    public ResponseEntity<ApiResponse<StockLevelResponse>> setStock(
            @PathVariable UUID id,
            @PathVariable UUID productId,
            @RequestParam @Min(0) int quantity,
            @RequestParam(required = false) Integer reorderLevel) {

        return ResponseEntity.ok(ApiResponse.ok(
                warehouseService.setStock(id, productId, quantity, reorderLevel),
                "Stock updated"));
    }

    @GetMapping("/stock/{productId}")
    @Operation(summary = "Where one product is",
            description = "Across every warehouse, so an operator can see that twelve units are "
                    + "six here and six there rather than twelve anywhere.")
    public ResponseEntity<ApiResponse<List<StockLevelResponse>>> locate(
            @PathVariable UUID productId) {

        return ResponseEntity.ok(ApiResponse.ok(warehouseService.locate(productId)));
    }

    // Deliberately not /stock/low. That would sit under the same prefix as /stock/{productId},
    // and whether "low" is read as a literal or as a product id would depend on Spring's pattern
    // precedence -- which is correct today and is not something worth relying on. A distinct path
    // cannot be got wrong.
    @GetMapping("/low-stock")
    @Operation(summary = "Everything at or below its reorder point",
            description = "Per building, because a shop can be short in Milan and fine in "
                    + "Amsterdam — a single global figure hides exactly that.")
    public ResponseEntity<ApiResponse<List<StockLevelResponse>>> lowStock() {
        return ResponseEntity.ok(ApiResponse.ok(warehouseService.lowStock()));
    }
}
