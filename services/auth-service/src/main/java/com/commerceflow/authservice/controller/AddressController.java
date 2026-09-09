package com.commerceflow.authservice.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerceflow.authservice.dto.AddressRequest;
import com.commerceflow.authservice.dto.AddressResponse;
import com.commerceflow.authservice.service.AddressBookService;
import com.commerceflow.common.dto.ApiResponse;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * A customer's own address book.
 *
 * <p>Everything here is scoped to the caller. There is no {@code userId} parameter anywhere in this
 * controller — not even an administrator-only one — because the id always comes from the token. An
 * endpoint that accepts a user id is an endpoint someone will eventually call with a different
 * one.
 *
 * <p>Addresses are a convenience: the checkout form reads them so people do not retype their
 * street. Orders copy what was submitted rather than pointing back here, so editing or deleting an
 * address can never disturb an order that has already been placed.
 */
@RestController
@RequestMapping("/api/users/me/addresses")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Addresses", description = "The signed-in customer's saved addresses")
public class AddressController {

    private final AddressBookService addressBook;

    @GetMapping
    @Operation(summary = "List my addresses",
            description = "Default first, then most recently changed — the order a checkout form "
                    + "should offer them in.")
    public ResponseEntity<ApiResponse<List<AddressResponse>>> list(
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(ApiResponse.ok(addressBook.list(require(caller).userId())));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one of my addresses")
    public ResponseEntity<ApiResponse<AddressResponse>> get(
            @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(ApiResponse.ok(addressBook.get(id, require(caller).userId())));
    }

    @PostMapping
    @Operation(summary = "Save a new address",
            description = "The first address saved becomes the default automatically.")
    public ResponseEntity<ApiResponse<AddressResponse>> create(
            @Valid @RequestBody AddressRequest request,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        AddressResponse created = addressBook.create(request, require(caller).userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created, "Address saved"));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace an address",
            description = "The id survives, so a client holding it stays valid.")
    public ResponseEntity<ApiResponse<AddressResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody AddressRequest request,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(ApiResponse.ok(
                addressBook.update(id, request, require(caller).userId()), "Address updated"));
    }

    @PostMapping("/{id}/default")
    @Operation(summary = "Make this my default address",
            description = "Demotes whichever address held the flag, in the same transaction.")
    public ResponseEntity<ApiResponse<AddressResponse>> makeDefault(
            @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(ApiResponse.ok(
                addressBook.makeDefault(id, require(caller).userId()), "Default address set"));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an address",
            description = "Orders that used it are unaffected — they kept their own copy. If the "
                    + "deleted address was the default, another is promoted.")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser caller) {

        addressBook.delete(id, require(caller).userId());
        return ResponseEntity.noContent().build();
    }

    private static AuthenticatedUser require(AuthenticatedUser caller) {
        if (caller == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return caller;
    }
}
