package com.commerceflow.orderservice.returns;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import com.commerceflow.orderservice.returns.ReturnDtos.CreateReturnRequest;
import com.commerceflow.orderservice.returns.ReturnDtos.ReturnDecisionRequest;
import com.commerceflow.orderservice.returns.ReturnDtos.ReturnReceiptRequest;
import com.commerceflow.orderservice.returns.ReturnDtos.ReturnResponse;
import com.commerceflow.orderservice.returns.ReturnDtos.ReturnableLine;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * Sending goods back.
 *
 * <p>A return is a new piece of business that refers to an order, not a state of the order — the
 * same arrangement as a shipment. See the migration that created the tables for why an order does
 * not gain a {@code RETURNED} status.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Returns", description = "Sending goods back, and getting the money back")
public class ReturnController {

    private final ReturnService returnService;

    // =============================================================================== customer

    @GetMapping("/orders/{orderId}/returnable")
    @Operation(summary = "What of this order can still be sent back",
            description = "Every line with what was ordered, what has already been claimed by a "
                    + "return, and what one unit gives back. Served before the customer chooses, "
                    + "so a form cannot offer a quantity that would be refused.")
    public ResponseEntity<ApiResponse<List<ReturnableLine>>> returnable(
            @PathVariable UUID orderId, @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(ApiResponse.ok(
                returnService.returnable(orderId, ReturnService.require(caller))));
    }

    @PostMapping("/orders/{orderId}/returns")
    @Operation(summary = "Ask to send items back",
            description = """
                    Only for an order that has **COMPLETED**. One that is still being paid for is \
                    cancelled instead — that unwinds the saga and puts the stock straight back, \
                    which is a different and cheaper operation.

                    The refund is worked out and frozen now, from the prices actually charged: a \
                    catalogue change or an expiring campaign between asking and refunding cannot \
                    alter what somebody gets back. Delivery is included only when the whole order \
                    comes back.""")
    public ResponseEntity<ApiResponse<ReturnResponse>> request(
            @PathVariable UUID orderId,
            @Valid @RequestBody CreateReturnRequest request,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                returnService.request(orderId, request, ReturnService.require(caller)),
                "We have your return request"));
    }

    @GetMapping("/orders/{orderId}/returns")
    @Operation(summary = "Returns raised against one order")
    public ResponseEntity<ApiResponse<List<ReturnResponse>>> forOrder(
            @PathVariable UUID orderId, @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(ApiResponse.ok(
                returnService.forOrder(orderId, ReturnService.require(caller))));
    }

    @GetMapping("/returns/mine")
    @Operation(summary = "Every return this customer has raised")
    public ResponseEntity<ApiResponse<List<ReturnResponse>>> mine(
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(ApiResponse.ok(returnService.mine(ReturnService.require(caller))));
    }

    @GetMapping("/returns/{returnId}")
    @Operation(summary = "One return",
            description = "A customer sees their own; somebody else's returns 404 rather than 403.")
    public ResponseEntity<ApiResponse<ReturnResponse>> getById(
            @PathVariable UUID returnId, @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(ApiResponse.ok(
                returnService.getById(returnId, ReturnService.require(caller))));
    }

    @PostMapping("/returns/{returnId}/cancel")
    @Operation(summary = "Call off a return",
            description = "Only while nobody has decided on it. Once it is approved the goods are "
                    + "on their way back, and cancelling would leave a parcel arriving that "
                    + "nothing expects.")
    public ResponseEntity<ApiResponse<ReturnResponse>> cancel(
            @PathVariable UUID returnId, @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(ApiResponse.ok(
                returnService.cancel(returnId, ReturnService.require(caller)),
                "Return cancelled"));
    }

    // ============================================================================== operator

    @GetMapping("/returns")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "The returns queue",
            description = "Filtered by state, oldest first — which is the order it should be "
                    + "worked in. Without a state, everything newest first.")
    public ResponseEntity<ApiResponse<PageResponse<ReturnResponse>>> queue(
            @RequestParam(required = false) ReturnStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        return ResponseEntity.ok(ApiResponse.ok(returnService.queue(status, page, size)));
    }

    @PostMapping("/returns/{returnId}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Approve a return",
            description = "The customer is told to send the goods back. Nothing is refunded yet — "
                    + "money follows the goods, not the promise of them.")
    public ResponseEntity<ApiResponse<ReturnResponse>> approve(
            @PathVariable UUID returnId,
            @Valid @RequestBody(required = false) ReturnDecisionRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {

        return ResponseEntity.ok(ApiResponse.ok(
                returnService.approve(returnId, note(request), ReturnService.require(operator)),
                "Return approved"));
    }

    @PostMapping("/returns/{returnId}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Refuse a return",
            description = "The note is shown to the customer and is the only place they will find "
                    + "out why. A refusal with no reason produces a support ticket instead.")
    public ResponseEntity<ApiResponse<ReturnResponse>> reject(
            @PathVariable UUID returnId,
            @Valid @RequestBody(required = false) ReturnDecisionRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {

        return ResponseEntity.ok(ApiResponse.ok(
                returnService.reject(returnId, note(request), ReturnService.require(operator)),
                "Return refused"));
    }

    @PostMapping("/returns/{returnId}/received")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "The goods are back",
            description = """
                    Recorded when the parcel has arrived and been checked. Separate from refunding                     on purpose: what came back is a warehouse fact and what is paid is a money                     decision, and one person often does the first without the authority for the                     second.

                    **`restock` is required and has no default.** Not everything that comes back                     can be sold again — a change of mind returns to the shelf, a cracked screen                     does not — and only the person holding the box can tell. `true` sends a                     restock command to Inventory Service for exactly the returned lines; `false`                     records the units as received and written off.""")
    public ResponseEntity<ApiResponse<ReturnResponse>> received(
            @PathVariable UUID returnId,
            @Valid @RequestBody ReturnReceiptRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {

        return ResponseEntity.ok(ApiResponse.ok(
                returnService.markReceived(returnId, request.restock(),
                        ReturnService.require(operator)),
                request.restock() ? "Goods received and back on sale" : "Goods received"));
    }

    @PostMapping("/returns/{returnId}/refund")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Send the money back",
            description = """
                    Asks Payment Service for the refund and leaves the return **REFUND_PENDING** \
                    until the answer arrives. Pressing it again while it is pending does nothing, \
                    so two people working the same queue cannot refund twice.

                    A failure puts the return back to **RECEIVED** with the provider's reason on \
                    it, so it can be tried again once whatever went wrong is fixed.""")
    public ResponseEntity<ApiResponse<ReturnResponse>> refund(
            @PathVariable UUID returnId,
            @AuthenticationPrincipal AuthenticatedUser operator) {

        return ResponseEntity.ok(ApiResponse.ok(
                returnService.refund(returnId, ReturnService.require(operator)),
                "Refund requested"));
    }

    /** An absent body means no note, which is allowed. */
    private static String note(ReturnDecisionRequest request) {
        return request == null ? null : request.note();
    }
}
