package com.commerceflow.paymentservice.controller;

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
import org.springframework.web.bind.annotation.RestController;

import com.commerceflow.common.dto.ApiResponse;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.UnauthorizedException;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.paymentservice.dto.PaymentResponse;
import com.commerceflow.paymentservice.dto.ProcessPaymentRequest;
import com.commerceflow.paymentservice.entity.PaymentStatus;
import com.commerceflow.paymentservice.service.PaymentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Payment API, per {@code contracts/apis.md}. */
@Validated
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Payments", description = "Charging customers and inspecting payment outcomes")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/process")
    @Operation(summary = "Charge an order manually",
            description = "The normal flow charges automatically on inventory.reserved. This "
                    + "endpoint exists for operator-driven retries and for clients paying outside "
                    + "that flow; it enforces the same one-payment-per-order guarantee.")
    public ResponseEntity<ApiResponse<PaymentResponse>> process(
            @Valid @RequestBody ProcessPaymentRequest request,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        PaymentResponse response = paymentService.processManually(request, requireCaller(caller));

        // A decline is a business outcome, not an error: the payment was recorded and the saga
        // was told. The caller still needs a status code that says "not paid".
        if (PaymentStatus.FAILED.name().equals(response.status())) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(ApiResponse.ok(response, "Payment declined: " + response.failureReason()));
        }
        return ResponseEntity.ok(ApiResponse.ok(response, "Payment approved"));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one payment")
    public ResponseEntity<ApiResponse<PaymentResponse>> getById(
            @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.getById(id, requireCaller(caller))));
    }

    @GetMapping("/order/{orderId}")
    @Operation(summary = "Fetch the payment for an order")
    public ResponseEntity<ApiResponse<PaymentResponse>> getByOrder(
            @PathVariable UUID orderId, @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(
                ApiResponse.ok(paymentService.getByOrderId(orderId, requireCaller(caller))));
    }

    private static AuthenticatedUser requireCaller(AuthenticatedUser caller) {
        if (caller == null) {
            throw new UnauthorizedException(ErrorCode.UNAUTHORIZED);
        }
        return caller;
    }
}
