package com.commerceflow.orderservice.cart;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
 * The signed-in customer's basket and wishlist.
 *
 * <p>Both are scoped to the token. There is no user id anywhere in these paths, for the same reason
 * as the address book: an endpoint that accepts one is an endpoint somebody eventually calls with
 * somebody else's.
 *
 * <p>A guest still has a basket — it lives in their browser until they sign in, at which point
 * {@code POST /api/cart/merge} folds it into the stored one.
 */
@Validated
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Basket", description = "The signed-in customer's basket and wishlist")
public class CartController {

    private final CartService cartService;
    private final WishlistService wishlistService;

    // =====================================================================================
    // Basket
    // =====================================================================================

    @GetMapping("/cart")
    @Operation(summary = "My basket",
            description = """
                    Priced from the live catalogue on every request, so a product repriced \
                    overnight shows its new price in the morning. Nothing on a basket is frozen — \
                    that only happens when the order is placed.

                    A line whose product is withdrawn, out of stock, or short of the quantity \
                    asked for is returned with an `issue` rather than being removed or reduced. \
                    Quietly trimming somebody's basket is the version of this that feels helpful \
                    and is not.""")
    public ResponseEntity<ApiResponse<CartResponse>> get(
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(ApiResponse.ok(cartService.get(require(caller).userId())));
    }

    @PutMapping("/cart/items")
    @Operation(summary = "Set the quantity of a product",
            description = "Sets rather than adds, which is what a quantity control does — an "
                    + "\"add\" that a client retries would leave the customer with four of "
                    + "something they asked for twice. Quantity 0 removes the line.")
    public ResponseEntity<ApiResponse<CartResponse>> put(
            @Valid @RequestBody CartLineRequest request,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(ApiResponse.ok(cartService.put(
                require(caller).userId(), request.productId(), request.quantity())));
    }

    @DeleteMapping("/cart/items/{productId}")
    @Operation(summary = "Remove a line")
    public ResponseEntity<ApiResponse<CartResponse>> remove(
            @PathVariable UUID productId, @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(
                ApiResponse.ok(cartService.remove(require(caller).userId(), productId)));
    }

    @DeleteMapping("/cart")
    @Operation(summary = "Empty the basket")
    public ResponseEntity<ApiResponse<CartResponse>> clear(
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(ApiResponse.ok(cartService.clear(require(caller).userId())));
    }

    @PostMapping("/cart/merge")
    @Operation(summary = "Fold a guest basket into mine",
            description = """
                    Called once, just after signing in, with whatever was in the browser's local \
                    basket.

                    Where both baskets hold the same product, the **larger** quantity wins rather \
                    than the sum. Adding two on a phone and two on a laptop is almost always one \
                    intention expressed twice; of the two ways to be wrong, leaving somebody with \
                    fewer than they wanted is the one they notice and can fix in a click.""")
    public ResponseEntity<ApiResponse<CartResponse>> merge(
            @Valid @RequestBody List<CartLineRequest> guestLines,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(ApiResponse.ok(
                cartService.merge(require(caller).userId(), guestLines), "Basket merged"));
    }

    // =====================================================================================
    // Wishlist
    // =====================================================================================

    @GetMapping("/wishlist")
    @Operation(summary = "My wishlist",
            description = "Priced live, so a saved item that has come down in price says so.")
    public ResponseEntity<ApiResponse<List<WishlistEntry>>> wishlist(
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(ApiResponse.ok(wishlistService.list(require(caller).userId())));
    }

    @PostMapping("/wishlist/{productId}")
    @Operation(summary = "Save an item",
            description = "Idempotent: saving twice leaves one entry, because pressing a heart "
                    + "twice means the same thing as pressing it once.")
    public ResponseEntity<Void> save(
            @PathVariable UUID productId, @AuthenticationPrincipal AuthenticatedUser caller) {

        wishlistService.add(require(caller).userId(), productId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/wishlist/{productId}")
    @Operation(summary = "Forget an item")
    public ResponseEntity<Void> forget(
            @PathVariable UUID productId, @AuthenticationPrincipal AuthenticatedUser caller) {

        wishlistService.remove(require(caller).userId(), productId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/wishlist/{productId}/move-to-cart")
    @Operation(summary = "Move a saved item into the basket",
            description = "Removed from the wishlist only once the basket has taken it. If the "
                    + "basket refuses, the customer still has it saved — the state they were in "
                    + "before they pressed anything.")
    public ResponseEntity<ApiResponse<CartResponse>> moveToCart(
            @PathVariable UUID productId,
            @RequestParam(defaultValue = "1") int quantity,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(ApiResponse.ok(
                wishlistService.moveToCart(require(caller).userId(), productId, quantity),
                "Moved to your basket"));
    }

    private static AuthenticatedUser require(AuthenticatedUser caller) {
        if (caller == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return caller;
    }
}
