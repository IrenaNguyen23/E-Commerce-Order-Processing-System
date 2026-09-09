package com.commerceflow.notificationservice.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.commerceflow.common.dto.ApiResponse;
import com.commerceflow.common.dto.PageResponse;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.UnauthorizedException;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.notificationservice.dto.NotificationResponse;
import com.commerceflow.notificationservice.service.NotificationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** Read-only view of what the platform has sent a customer. */
@Validated
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Notifications", description = "Delivery history")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @Operation(summary = "List notifications",
            description = "A customer sees their own; an ADMIN sees every notification.")
    public ResponseEntity<ApiResponse<PageResponse<NotificationResponse>>> list(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @Parameter(description = "PENDING, SENT or FAILED")
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {

        PageResponse<NotificationResponse> result = notificationService.search(
                requireCaller(caller), status, page, size, sortBy, direction);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one notification")
    public ResponseEntity<ApiResponse<NotificationResponse>> getById(
            @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(
                ApiResponse.ok(notificationService.getById(id, requireCaller(caller))));
    }

    private static AuthenticatedUser requireCaller(AuthenticatedUser caller) {
        if (caller == null) {
            throw new UnauthorizedException(ErrorCode.UNAUTHORIZED);
        }
        return caller;
    }
}
