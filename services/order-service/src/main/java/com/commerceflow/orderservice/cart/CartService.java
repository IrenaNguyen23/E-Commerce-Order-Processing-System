package com.commerceflow.orderservice.cart;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.orderservice.config.OrderProperties;
import com.commerceflow.orderservice.dto.CatalogProduct;
import com.commerceflow.orderservice.service.ProductCatalogClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The customer's basket, kept on the server.
 *
 * <h2>Priced live, every time</h2>
 *
 * <p>A basket holds product ids and quantities. Names, prices, stock and pictures are read from the
 * catalogue on every request, so a product repriced overnight shows its new price in the morning.
 * That is the opposite of an order, which freezes everything — and the difference is the whole
 * distinction between an intention and a contract.
 *
 * <h2>Problems are reported, never silently fixed</h2>
 *
 * <p>A basket can go stale in three ways: a product is withdrawn, it goes out of stock, or there is
 * less of it than the customer wanted. None of them removes a line or reduces a quantity
 * automatically. Each is reported as an {@code issue} on the line, and the customer decides.
 *
 * <p>Silently trimming a basket is the version of this that feels helpful and is not: somebody
 * comes back to buy three of something, is quietly given one, and finds out from the invoice.
 *
 * <h2>Merging a guest basket</h2>
 *
 * <p>When somebody signs in with items already in a local basket, the two are merged by taking the
 * <b>larger</b> quantity of each product rather than the sum. Adding two on a phone and two on a
 * laptop is almost always the same intention expressed twice, not an intention to buy four — and
 * of the two ways to be wrong, leaving somebody with fewer than they wanted is the one they will
 * notice and can fix in one click.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository carts;
    private final ProductCatalogClient catalogue;
    private final OrderProperties properties;

    /** The basket, priced from the live catalogue. */
    @Transactional
    public CartResponse get(UUID userId) {
        return price(cartFor(userId));
    }

    /**
     * Sets the quantity of a product.
     *
     * <p>A quantity of zero removes the line, which is what a quantity control set to zero means.
     * The alternative — refusing it and making the client call DELETE — is a rule the client has
     * to remember rather than one the server can simply honour.
     */
    @Transactional
    public CartResponse put(UUID userId, UUID productId, int quantity) {
        Cart cart = cartFor(userId);

        if (quantity <= 0) {
            cart.remove(productId);
            return price(save(cart));
        }

        if (quantity > properties.getMaxQuantityPerLine()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "You can order at most " + properties.getMaxQuantityPerLine()
                            + " of one item");
        }

        if (cart.find(productId).isEmpty()
                && cart.getItems().size() >= properties.getMaxItemsPerOrder()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "A basket can hold " + properties.getMaxItemsPerOrder()
                            + " different items. Remove something first.");
        }

        // Checked here so a basket cannot hold a product that does not exist. Availability is
        // deliberately *not* checked: an out-of-stock item may stay in the basket, flagged, and
        // the customer decides whether to wait.
        CatalogProduct product = catalogue.lookup(List.of(productId)).get(productId);
        if (product == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND,
                    "Unknown product " + productId);
        }

        cart.put(productId, quantity);
        return price(save(cart));
    }

    @Transactional
    public CartResponse remove(UUID userId, UUID productId) {
        Cart cart = cartFor(userId);
        cart.remove(productId);
        return price(save(cart));
    }

    @Transactional
    public CartResponse clear(UUID userId) {
        Cart cart = cartFor(userId);
        cart.getItems().clear();
        return price(save(cart));
    }

    /**
     * Folds a guest basket into the signed-in one.
     *
     * <p>Larger quantity wins per product; see the class comment for why that is not a sum.
     */
    @Transactional
    public CartResponse merge(UUID userId, List<CartLineRequest> guestLines) {
        Cart cart = cartFor(userId);

        for (CartLineRequest line : guestLines) {
            if (line.quantity() <= 0) {
                continue;
            }
            int existing = cart.find(line.productId()).map(CartItem::getQuantity).orElse(0);
            int merged = Math.min(Math.max(existing, line.quantity()),
                    properties.getMaxQuantityPerLine());
            if (cart.find(line.productId()).isEmpty()
                    && cart.getItems().size() >= properties.getMaxItemsPerOrder()) {
                log.info("Dropped {} while merging a guest basket: the signed-in basket is full",
                        line.productId());
                continue;
            }
            cart.put(line.productId(), merged);
        }

        log.info("Merged {} guest line(s) into the basket of customer {}", guestLines.size(),
                userId);
        return price(save(cart));
    }

    /**
     * Empties the basket after an order is placed.
     *
     * <p>Called from the checkout, in its transaction, so a basket cannot survive the order it
     * became — a customer returning to a page still showing the items they just bought will buy
     * them again.
     */
    @Transactional
    public void emptyAfterCheckout(UUID userId) {
        carts.findByUserId(userId).ifPresent(cart -> {
            cart.getItems().clear();
            save(cart);
        });
    }

    // =====================================================================================

    private Cart cartFor(UUID userId) {
        return carts.findByUserId(userId).orElseGet(() -> {
            Instant now = Instant.now();
            return carts.save(Cart.builder()
                    .id(UUID.randomUUID())
                    .userId(userId)
                    .createdAt(now)
                    .updatedAt(now)
                    .items(new ArrayList<>())
                    .build());
        });
    }

    private Cart save(Cart cart) {
        cart.setUpdatedAt(Instant.now());
        return carts.save(cart);
    }

    /**
     * Attaches live catalogue data to every line.
     *
     * <p>One lookup for the whole basket. A product that has since been deleted outright keeps its
     * line, marked unavailable, rather than vanishing — a line that disappears without explanation
     * is indistinguishable from one the customer removed themselves.
     */
    private CartResponse price(Cart cart) {
        List<UUID> ids = cart.getItems().stream().map(CartItem::getProductId).toList();
        Map<UUID, CatalogProduct> catalog = ids.isEmpty() ? Map.of() : catalogue.lookup(ids);

        List<CartLineResponse> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        String currency = null;
        boolean checkoutable = !cart.getItems().isEmpty();

        for (CartItem item : cart.getItems()) {
            CatalogProduct product = catalog.get(item.getProductId());

            if (product == null) {
                lines.add(CartLineResponse.unavailable(item.getProductId(), item.getQuantity(),
                        "This product is no longer in the catalogue"));
                checkoutable = false;
                continue;
            }

            String issue = issueWith(product, item.getQuantity());
            BigDecimal lineTotal = product.price().multiply(BigDecimal.valueOf(item.getQuantity()));

            lines.add(new CartLineResponse(product.id(), product.sku(), product.name(),
                    product.imageUrl(), item.getQuantity(), product.price(), lineTotal,
                    product.currency(), product.availableQuantity(), issue == null, issue,
                    item.getAddedAt()));

            if (issue != null) {
                checkoutable = false;
            } else {
                total = total.add(lineTotal);
                currency = product.currency();
            }
        }

        return new CartResponse(cart.getId(), lines, cart.totalQuantity(), total,
                currency == null ? "EUR" : currency, checkoutable, cart.getUpdatedAt());
    }

    /** What is wrong with a line, in the words the customer needs, or {@code null}. */
    private static String issueWith(CatalogProduct product, int wanted) {
        if (!product.active()) {
            return "No longer for sale";
        }
        if (product.availableQuantity() <= 0) {
            return "Out of stock";
        }
        if (product.availableQuantity() < wanted) {
            return "Only " + product.availableQuantity() + " left";
        }
        return null;
    }
}
