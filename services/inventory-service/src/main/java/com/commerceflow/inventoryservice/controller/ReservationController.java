package com.commerceflow.inventoryservice.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerceflow.common.dto.ApiResponse;
import com.commerceflow.inventoryservice.dto.ReleaseReservationRequest;
import com.commerceflow.inventoryservice.dto.ReservationReleaseResponse;
import com.commerceflow.inventoryservice.service.InventoryReservationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * The operator lever for stock that is stuck.
 *
 * <p>Nothing here is part of the saga. It exists because {@code StaleReservationMonitor} can
 * detect a hold that has outlived its order but deliberately will not release it — from inside
 * Inventory, a hold whose saga vanished looks identical to one whose saga is parked mid-payment,
 * and releasing the second oversells stock the customer may already have paid for. This is where
 * a person, having established which it is, acts on the answer.
 */
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
@Tag(name = "Reservations", description = "Operator actions on stock holds")
public class ReservationController {

    private final InventoryReservationService reservationService;

    /**
     * Releases the hold on an order, putting every unit back on sale.
     *
     * <p>Administrator only, and audited: the reason is recorded on the reservation and an
     * {@code inventory.released} event is published, so the release shows up in the same place
     * every other stock movement does.
     */
    @PostMapping("/{orderId}/release")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Release a stuck inventory hold",
            description = "Puts the held units back on sale. Intended for a hold whose saga will "
                    + "never finish. Check the order's saga first: one parked at the payment step "
                    + "may already have been charged, and releasing it oversells the stock.")
    public ResponseEntity<ApiResponse<ReservationReleaseResponse>> release(
            @PathVariable UUID orderId,
            @Valid @RequestBody(required = false) ReleaseReservationRequest request) {

        String reason = request == null ? null : request.reason();
        return ResponseEntity.ok(ApiResponse.ok(reservationService.releaseManually(orderId, reason)));
    }
}
