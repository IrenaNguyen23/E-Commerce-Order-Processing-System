package com.commerceflow.orderservice.fulfilment;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.commerceflow.common.dto.ApiResponse;
import com.commerceflow.common.dto.PageResponse;
import com.commerceflow.common.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * Parcels.
 *
 * <p>Customers can see the shipments of their own orders. Everything that changes one is
 * administrator-only — this is the warehouse's API, and the only reason it lives in Order Service
 * is that a shipment is meaningless without the order it belongs to.
 *
 * <p>Note what is absent: nothing here moves an order's status. An order is {@code COMPLETED} and
 * its parcel is {@code IN_TRANSIT}; see {@link ShipmentStatus} for why those are deliberately two
 * different lifecycles.
 */
@Validated
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Shipments", description = "Getting parcels to people")
public class ShipmentController {

    private final ShipmentService shipmentService;

    @GetMapping("/orders/{orderId}/shipments")
    @Operation(summary = "Shipments for an order",
            description = "Usually one. Two after a return and a redelivery. A customer sees only "
                    + "their own; somebody else's order returns 404 rather than 403.")
    public ResponseEntity<ApiResponse<List<ShipmentResponse>>> forOrder(
            @PathVariable UUID orderId, @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(ApiResponse.ok(
                shipmentService.forOrder(orderId, ShipmentService.require(caller))));
    }

    @GetMapping("/shipments/track/{trackingNumber}")
    @Operation(summary = "Look up a parcel by its tracking number",
            description = "For a customer who has the number to hand and not the order. Still "
                    + "scoped to the caller: a tracking number is not a password.")
    public ResponseEntity<ApiResponse<ShipmentResponse>> track(
            @PathVariable String trackingNumber,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(ApiResponse.ok(
                shipmentService.track(trackingNumber, ShipmentService.require(caller))));
    }

    @PostMapping("/orders/{orderId}/shipments")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Open a shipment",
            description = """
                    Only for an order the saga has finished with. An order still being paid for \
                    has stock that is reserved rather than sold, and dispatching against a \
                    reservation means sending something the shop may still have to refund.

                    Carrier and tracking number are optional here: a warehouse usually opens the \
                    shipment when it starts picking and only learns them when the parcel is \
                    handed over.""")
    public ResponseEntity<ApiResponse<ShipmentResponse>> create(
            @PathVariable UUID orderId,
            @Valid @RequestBody(required = false) CreateShipmentRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {

        CreateShipmentRequest payload = request == null
                ? new CreateShipmentRequest(null, null, null)
                : request;

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                shipmentService.create(orderId, payload, ShipmentService.require(operator)),
                "Shipment opened"));
    }

    @PostMapping("/shipments/{shipmentId}/events")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Record what has happened to a parcel",
            description = """
                    Appends to the history and moves the current state.

                    Transitions are looser than the order saga's on purpose: a delivered parcel \
                    can be returned, and a failed delivery can go back into transit, because both \
                    happen to real parcels. Only nonsense is refused — a cancelled shipment coming \
                    back to life, a delivered one going back to being picked.

                    Dispatch, a failed attempt, delivery and a return email the customer. Picking \
                    does not: an email per internal state change teaches people to ignore mail \
                    from the shop.""")
    public ResponseEntity<ApiResponse<ShipmentResponse>> advance(
            @PathVariable UUID shipmentId,
            @Valid @RequestBody UpdateShipmentRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {

        return ResponseEntity.ok(ApiResponse.ok(
                shipmentService.advance(shipmentId, request, ShipmentService.require(operator)),
                "Shipment updated"));
    }

    @GetMapping("/shipments")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "The warehouse queue",
            description = "Everything in one state, oldest first — which is the order it should "
                    + "be worked in.")
    public ResponseEntity<ApiResponse<PageResponse<ShipmentResponse>>> queue(
            @RequestParam(defaultValue = "PENDING") ShipmentStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        return ResponseEntity.ok(ApiResponse.ok(shipmentService.queue(status, page, size)));
    }
}
