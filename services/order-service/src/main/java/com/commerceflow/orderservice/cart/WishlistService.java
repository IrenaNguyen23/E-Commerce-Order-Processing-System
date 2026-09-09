package com.commerceflow.orderservice.cart;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.orderservice.dto.CatalogProduct;
import com.commerceflow.orderservice.service.ProductCatalogClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Things a customer wants to remember.
 *
 * <p>Priced live, like a basket and unlike an order. A wishlist that quoted the price something was
 * when it was saved would be showing a figure nobody ever offered — and the one thing a wishlist is
 * genuinely useful for is noticing that something has come down in price.
 *
 * <p>Adding is idempotent: saving something twice leaves one entry rather than two, because "add to
 * wishlist" pressed twice is one intention. That is enforced by a unique constraint as well as by
 * the check here, since two taps can arrive together.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WishlistService {

    /** A generous ceiling on an authenticated write, not a judgement about how much to want. */
    private static final int MAX_ITEMS = 200;

    private final WishlistRepository wishlist;
    private final CartService cartService;
    private final ProductCatalogClient catalogue;

    @Transactional(readOnly = true)
    public List<WishlistEntry> list(UUID userId) {
        List<WishlistItem> saved = wishlist.findByUserIdOrderByAddedAtDesc(userId);
        if (saved.isEmpty()) {
            return List.of();
        }

        Map<UUID, CatalogProduct> catalog = catalogue.lookup(
                saved.stream().map(WishlistItem::getProductId).toList());

        return saved.stream().map(item -> {
            CatalogProduct product = catalog.get(item.getProductId());
            if (product == null) {
                // Kept and marked, like a basket line. A saved item that silently disappears
                // leaves the customer wondering whether they imagined saving it.
                return WishlistEntry.unavailable(item.getProductId(), item.getAddedAt());
            }
            return new WishlistEntry(product.id(), product.sku(), product.name(),
                    product.imageUrl(), product.price(), product.currency(),
                    product.active() && product.availableQuantity() > 0,
                    product.availableQuantity(), item.getAddedAt());
        }).toList();
    }

    @Transactional
    public void add(UUID userId, UUID productId) {
        if (wishlist.existsByUserIdAndProductId(userId, productId)) {
            // Not an error. Pressing a heart twice means the same thing as pressing it once.
            return;
        }
        if (wishlist.countByUserId(userId) >= MAX_ITEMS) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "Your wishlist holds " + MAX_ITEMS + " items. Remove one to save another.");
        }
        if (catalogue.lookup(List.of(productId)).get(productId) == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND,
                    "Unknown product " + productId);
        }

        wishlist.save(WishlistItem.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .productId(productId)
                .addedAt(Instant.now())
                .build());
    }

    @Transactional
    public void remove(UUID userId, UUID productId) {
        wishlist.findByUserIdAndProductId(userId, productId).ifPresent(wishlist::delete);
    }

    /**
     * Moves a saved item into the basket.
     *
     * <p>Removes it from the wishlist only after the basket has accepted it. If the basket refuses
     * — the product is gone, the basket is full — the customer still has it saved, which is the
     * state they were in before they pressed anything.
     */
    @Transactional
    public CartResponse moveToCart(UUID userId, UUID productId, int quantity) {
        CartResponse cart = cartService.put(userId, productId, Math.max(1, quantity));
        remove(userId, productId);
        log.info("Customer {} moved {} from their wishlist into their basket", userId, productId);
        return cart;
    }

    @Transactional(readOnly = true)
    public boolean contains(UUID userId, UUID productId) {
        return wishlist.existsByUserIdAndProductId(userId, productId);
    }
}
