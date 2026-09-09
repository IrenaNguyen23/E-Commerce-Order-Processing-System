package com.commerceflow.authservice.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.commerceflow.authservice.dto.UpdateUserRequest;
import com.commerceflow.authservice.dto.UserResponse;
import com.commerceflow.authservice.service.AccountErasureService;
import com.commerceflow.authservice.service.UserAdminService;
import com.commerceflow.common.dto.ApiResponse;
import com.commerceflow.common.dto.PageResponse;
import com.commerceflow.common.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * Account administration.
 *
 * <p>Every endpoint is administrator-only, and every response is the public projection of an
 * account — the password hash has no representation in this API at all, not even an omitted one.
 *
 * <p>There is no endpoint to create an account here and none to set someone's password. Accounts
 * are created by registering, and passwords are chosen by the person they belong to; an operator
 * who needs to help someone in sends them a reset link. That keeps one property worth keeping:
 * nobody but the account holder has ever known their credential.
 */
@Validated
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "Accounts", description = "Back-office account administration")
public class UserAdminController {

    private final UserAdminService userAdminService;
    private final AccountErasureService erasureService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List accounts", description = "Paged, with optional filters.")
    public ResponseEntity<ApiResponse<PageResponse<UserResponse>>> list(
            @Parameter(description = "Free text over email and name")
            @RequestParam(required = false) String search,
            @Parameter(description = "CUSTOMER or ADMIN")
            @RequestParam(required = false) String role,
            @Parameter(description = "Filter by whether the account can sign in")
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {

        return ResponseEntity.ok(ApiResponse.ok(
                userAdminService.search(search, role, enabled, page, size, sortBy, direction)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Fetch one account")
    public ResponseEntity<ApiResponse<UserResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(userAdminService.getById(id)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Enable, disable, or change the roles of an account",
            description = """
                    Omitted fields are left alone.

                    Disabling **signs the account out everywhere** — every refresh token is \
                    revoked, so "disabled" means disabled now rather than whenever the token \
                    happened to expire.

                    Two changes are refused: locking yourself out, and removing the last enabled \
                    administrator. Both leave a platform that can only be recovered from a \
                    database console.""")
    public ResponseEntity<ApiResponse<UserResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(ApiResponse.ok(
                userAdminService.update(id, request, caller), "Account updated"));
    }

    @PostMapping("/{id}/unlock-signin")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Release a sign-in lock",
            description = """
                    Five wrong passwords lock an account for fifteen minutes. The lock releases                     itself, so this exists for the case where waiting is the wrong answer:                     somebody who mistyped on a phone keyboard and is on the telephone now.

                    It does **not** touch whether the account is enabled. A lock is a temporary                     consequence of traffic; enabled is a decision about the account. Conflating                     them would let anyone shut a customer out permanently by typing a wrong                     password often enough.""")
    public ResponseEntity<ApiResponse<Void>> unlockSignIn(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        userAdminService.unlockSignIn(id, caller);
        return ResponseEntity.ok(ApiResponse.ok(null, "Sign-in unlocked"));
    }

    @DeleteMapping("/{id}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Erase an account",
            description = """
                    Your own, or anybody's if you are an administrator. Somebody else's returns                     404 rather than 403.

                    **Anonymises rather than deletes.** Orders reference the account and orders                     are financial records that have to survive — removing the row would take the                     money with it. The address becomes a non-routable placeholder, the name and                     phone go, every session is revoked, and the account can never be signed into                     again. Saved addresses are deleted outright because nothing else needs them.

                    Order Service and Inventory Service are told over `user.erased` and scrub                     their own copies — the name on the order snapshot, the author of a review.                     That is eventually consistent by design: erasure has a legal deadline                     measured in weeks, not the seconds a payment does.

                    **There is no undo.** Anonymisation destroys what would be needed to reverse                     it, which is the point.""")
    public ResponseEntity<Void> erase(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        erasureService.erase(id, AccountErasureService.require(caller));
        return ResponseEntity.noContent().build();
    }
}
