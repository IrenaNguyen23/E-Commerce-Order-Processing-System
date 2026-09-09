package com.commerceflow.orderservice.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.UnauthorizedException;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.orderservice.dto.CancelOrderRequest;
import com.commerceflow.orderservice.dto.CreateOrderRequest;
import com.commerceflow.orderservice.dto.OrderResponse;
import com.commerceflow.orderservice.service.OrderCancellationService;
import com.commerceflow.orderservice.service.OrderCommandService;
import com.commerceflow.orderservice.service.OrderQueryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * Order API, per {@code contracts/apis.md}.
 *
 * <p>Writes go to {@link OrderCommandService}, reads to {@link OrderQueryService} — the HTTP layer
 * is where the CQRS split becomes visible.
 */
@Validated
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Orders", description = "Placing and tracking orders")
public class OrderController {

    private final OrderCommandService commandService;
    private final OrderCancellationService cancellationService;
    private final OrderQueryService queryService;

    @PostMapping
    @Operation(summary = "Place an order",
            description = "Persists the order and starts the saga. Responds as soon "
                    + "as the order is accepted, with status CREATED; stock reservation and "
                    + "payment then run asynchronously. Poll GET /api/orders/{id} to follow it.")
    public ResponseEntity<ApiResponse<OrderResponse>> place(
            @Valid @RequestBody CreateOrderRequest request,
            @AuthenticationPrincipal AuthenticatedUser customer) {

        OrderResponse created = commandService.placeOrder(request, requireCaller(customer));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created, "Order accepted, processing has started"));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one order",
            description = "Served from the CQRS read model. A customer may only read their own "
                    + "orders; an unknown and a foreign order both return 404.")
    public ResponseEntity<ApiResponse<OrderResponse>> getById(
            @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(ApiResponse.ok(queryService.getById(id, requireCaller(caller))));
    }

    @GetMapping("/number/{orderNumber}")
    @Operation(summary = "Fetch one order by its human readable number")
    public ResponseEntity<ApiResponse<OrderResponse>> getByNumber(
            @PathVariable String orderNumber, @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(
                ApiResponse.ok(queryService.getByOrderNumber(orderNumber, requireCaller(caller))));
    }

    @GetMapping
    @Operation(summary = "List orders",
            description = "A customer sees their own orders; an ADMIN sees every order and may "
                    + "narrow the result with the userId filter.")
    public ResponseEntity<ApiResponse<PageResponse<OrderResponse>>> list(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @Parameter(description = "CREATED, INVENTORY_RESERVED, PAID, COMPLETED or CANCELLED")
            @RequestParam(required = false) String status,
            @Parameter(description = "ADMIN only; ignored for customers")
            @RequestParam(required = false) UUID userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {

        PageResponse<OrderResponse> result = queryService.search(
                requireCaller(caller), status, userId, page, size, sortBy, direction);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    private static AuthenticatedUser requireCaller(AuthenticatedUser caller) {
        if (caller == null) {
            throw new UnauthorizedException(ErrorCode.UNAUTHORIZED);
        }
        return caller;
    }

    @PostMapping("/{id}/cancel")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Cancel an order",
            description = """
                    Cancels the order and unwinds whatever it had already done: a refund if it was                     paid for, and the stock back either way.

                    The order is marked cancelled immediately — it will not be fulfilled, and that                     is knowable straight away. Whether the money has arrived back is the                     **payment's** status, not the order's, so check `GET /api/payments/order/{id}`                     for that.

                    Refused with `409` while the saga is still running. An order mid-flight has a                     command outstanding, and cancelling into that race risks refunding a charge                     the system has not finished recording. It settles in seconds.""")
    public ResponseEntity<ApiResponse<OrderResponse>> cancel(
            @PathVariable UUID id,
            @RequestBody(required = false) CancelOrderRequest request,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        String reason = request == null ? null : request.reason();
        return ResponseEntity.ok(ApiResponse.ok(
                cancellationService.cancel(id, reason, caller), "Order cancelled"));
    }
}
