package com.commerceflow.authservice.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerceflow.authservice.dto.AuthResponse;
import com.commerceflow.authservice.dto.GuestSessionRequest;
import com.commerceflow.authservice.dto.LoginRequest;
import com.commerceflow.authservice.dto.RefreshTokenRequest;
import com.commerceflow.authservice.dto.RegisterRequest;
import com.commerceflow.authservice.dto.UserResponse;
import com.commerceflow.authservice.dto.ForgotPasswordRequest;
import com.commerceflow.authservice.dto.ResendVerificationRequest;
import com.commerceflow.authservice.dto.ResetPasswordRequest;
import com.commerceflow.authservice.dto.VerifyEmailRequest;
import com.commerceflow.authservice.service.AccountRecoveryService;
import com.commerceflow.authservice.service.AuthService;
import com.commerceflow.authservice.service.GuestSessionService;
import com.commerceflow.common.dto.ApiResponse;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.UnauthorizedException;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.common.security.JwtTokenProvider;
import com.commerceflow.common.security.SecurityHeaders;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Public authentication API.
 *
 * <p>Thin by design: it only adapts HTTP to {@link AuthService}, per {@code coding-standard.md}.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Registration, login and token lifecycle")
public class AuthController {

    private final AuthService authService;
    private final GuestSessionService guestSessionService;
    private final AccountRecoveryService recoveryService;

    @PostMapping("/guest")
    @Operation(summary = "Check out without an account",
            description = """
                    Creates an account with no password and returns a normal session for it.

                    A guest is a real customer as far as everything downstream is concerned —                     the basket, the order, the payment and the saga are unchanged. What they do                     not have is a password, so they cannot sign in until they set one through                     the ordinary reset flow, at which point their order history is already there.

                    **An address that already has an account is refused with `409`.** This                     endpoint hands out a session in exchange for an email address and nothing                     else; returning one for an existing account would let anyone type your                     address and be signed in as you.""")
    public ResponseEntity<ApiResponse<AuthResponse>> guest(
            @Valid @RequestBody GuestSessionRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(guestSessionService.start(request),
                        "You can check out now"));
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new customer account")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "Account created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "Email already registered")
    })
    public ResponseEntity<ApiResponse<UserResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        UserResponse created = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created, "Account created"));
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange credentials for an access and refresh token pair")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Token pair issued"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "Invalid credentials")
    })
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request,
                                                           HttpServletRequest httpRequest) {
        AuthResponse response = authService.login(request, userAgent(httpRequest), clientIp(httpRequest));
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate a refresh token into a fresh token pair")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @Valid @RequestBody RefreshTokenRequest request, HttpServletRequest httpRequest) {
        AuthResponse response =
                authService.refresh(request, userAgent(httpRequest), clientIp(httpRequest));
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/logout")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Revoke the current access token and every refresh token of the account")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest httpRequest) {
        String token = JwtTokenProvider.resolveBearerToken(
                httpRequest.getHeader(SecurityHeaders.AUTHORIZATION));
        if (token == null) {
            throw new UnauthorizedException(ErrorCode.UNAUTHORIZED, "A bearer token is required");
        }
        authService.logout(token);
        return ResponseEntity.ok(ApiResponse.message("Logged out"));
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Return the account behind the current access token")
    public ResponseEntity<ApiResponse<UserResponse>> me(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        if (principal == null) {
            throw new UnauthorizedException(ErrorCode.UNAUTHORIZED);
        }
        return ResponseEntity.ok(ApiResponse.ok(authService.currentUser(principal.userId())));
    }

    private static String userAgent(HttpServletRequest request) {
        return request.getHeader("User-Agent");
    }

    /** Honours {@code X-Forwarded-For} because the gateway is always in front of this service. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    // =====================================================================================
    // Account recovery
    // =====================================================================================

    @PostMapping("/forgot-password")
    @Operation(
            summary = "Ask for a password reset link",
            description = """
                    Always answers the same way, whether or not the address is registered. An \
                    endpoint that responded differently would let anyone check which addresses \
                    have accounts, so the message says *if an account exists* rather than \
                    claiming an email was sent.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "202", description = "Request accepted")
    })
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        recoveryService.requestPasswordReset(request.email());
        return ResponseEntity.accepted().body(ApiResponse.message(
                "If an account exists for that address, a reset link is on its way."));
    }

    @PostMapping("/reset-password")
    @Operation(
            summary = "Set a new password using an emailed token",
            description = """
                    Single use, and short lived. Completing a reset **signs the account out \
                    everywhere**: someone resetting their password may be locking an intruder \
                    out, and leaving that session alive would defeat the point.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Password changed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "The link is unknown, used or expired")
    })
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        recoveryService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.ok(ApiResponse.message(
                "Your password has been changed. Please sign in again."));
    }

    @PostMapping("/verify-email")
    @Operation(summary = "Confirm an email address using an emailed token")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Address confirmed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "The link is unknown, used or expired")
    })
    public ResponseEntity<ApiResponse<Void>> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest request) {
        recoveryService.verifyEmail(request.token());
        return ResponseEntity.ok(ApiResponse.message("Your email address is confirmed."));
    }

    @PostMapping("/resend-verification")
    @Operation(
            summary = "Ask for another confirmation link",
            description = "Answers identically for an unknown address, a verified one and an "
                    + "unverified one, for the same reason as forgot-password.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "202", description = "Request accepted")
    })
    public ResponseEntity<ApiResponse<Void>> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request) {
        recoveryService.resendVerification(request.email());
        return ResponseEntity.accepted().body(ApiResponse.message(
                "If that address needs confirming, a new link is on its way."));
    }
}
