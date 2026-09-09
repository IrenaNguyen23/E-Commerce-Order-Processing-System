package com.commerceflow.orderservice.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.orderservice.config.OrderProperties;
import com.commerceflow.orderservice.dto.CatalogProduct;
import com.commerceflow.orderservice.service.ProductCatalogClient;

/**
 * The basket.
 *
 * <p>Two things here are worth more than the rest, and both are about restraint. A basket must not
 * remember prices, and it must not quietly fix itself. Each is a small piece of code and each
 * replaces a version that feels more helpful and is worse for the customer.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartServiceTest {

    private static final UUID LAPTOP = UUID.randomUUID();
    private static final UUID HEADSET = UUID.randomUUID();

    @Mock
    private CartRepository carts;

    @Mock
    private ProductCatalogClient catalogue;

    private CartService service;
    private UUID userId;
    private Cart cart;

    @BeforeEach
    void setUp() {
        service = new CartService(carts, catalogue, new OrderProperties());
        userId = UUID.randomUUID();

        cart = Cart.builder()
                .id(UUID.randomUUID()).userId(userId)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .items(new ArrayList<>()).build();

        when(carts.findByUserId(userId)).thenReturn(Optional.of(cart));
        when(carts.save(any(Cart.class))).thenAnswer(call -> call.getArgument(0));
        stock(LAPTOP, "1899.00", 50, true);
    }

    private void stock(UUID productId, String price, int available, boolean active) {
        CatalogProduct product = new CatalogProduct(productId, "CF-1", "Laptop", "Computers",
                "computers", null, new BigDecimal(price), "EUR", active, available);
        when(catalogue.lookup(List.of(productId))).thenReturn(Map.of(productId, product));
        when(catalogue.lookup(anyList())).thenAnswer(call -> {
            List<UUID> ids = call.getArgument(0);
            return ids.contains(productId) ? Map.of(productId, product) : Map.of();
        });
    }

    @Nested
    @DisplayName("Prices are live")
    class LivePricing {

        @Test
        @DisplayName("the basket shows today's price, not the price when it was added")
        void pricesAreReadLive() {
            service.put(userId, LAPTOP, 1);

            // Repriced overnight. Nothing on the cart row changed — there is nothing on it to
            // change — so the basket simply shows the new figure.
            stock(LAPTOP, "1699.00", 50, true);

            assertThat(service.get(userId).lines().get(0).unitPrice())
                    .isEqualByComparingTo(new BigDecimal("1699.00"));
        }

        @Test
        @DisplayName("the total covers only the lines that can actually be bought")
        void totalExcludesUnbuyableLines() {
            service.put(userId, LAPTOP, 2);
            stock(LAPTOP, "1899.00", 0, true);

            CartResponse basket = service.get(userId);

            // A total including something out of stock is a number the checkout will not honour.
            assertThat(basket.total()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(basket.checkoutable()).isFalse();
        }
    }

    @Nested
    @DisplayName("Problems are reported, not fixed")
    class Problems {

        @Test
        @DisplayName("a short line keeps the quantity asked for and says how many are left")
        void shortStockIsFlaggedNotTrimmed() {
            service.put(userId, LAPTOP, 5);
            stock(LAPTOP, "1899.00", 2, true);

            CartLineResponse line = service.get(userId).lines().get(0);

            // Silently reducing 5 to 2 is the version that feels helpful: somebody comes back to
            // buy five, is quietly given two, and finds out from the invoice.
            assertThat(line.quantity()).isEqualTo(5);
            assertThat(line.issue()).isEqualTo("Only 2 left");
            assertThat(line.available()).isFalse();
        }

        @Test
        @DisplayName("a withdrawn product stays in the basket, marked")
        void withdrawnProductIsFlagged() {
            service.put(userId, LAPTOP, 1);
            stock(LAPTOP, "1899.00", 10, false);

            assertThat(service.get(userId).lines().get(0).issue()).isEqualTo("No longer for sale");
        }

        @Test
        @DisplayName("a product deleted from the catalogue leaves a marked line, not a gap")
        void deletedProductLeavesALine() {
            service.put(userId, LAPTOP, 1);
            when(catalogue.lookup(anyList())).thenReturn(Map.of());

            CartResponse basket = service.get(userId);

            // A line that disappears without explanation is indistinguishable from one the
            // customer removed themselves, and "where did that go?" has no answer anywhere.
            assertThat(basket.lines()).hasSize(1);
            assertThat(basket.lines().get(0).issue()).contains("no longer in the catalogue");
            assertThat(basket.checkoutable()).isFalse();
        }

        @Test
        @DisplayName("an out-of-stock product may still be added, flagged")
        void outOfStockCanStillBeAdded() {
            stock(LAPTOP, "1899.00", 0, true);

            // The customer decides whether to wait. Refusing the add would be the server making
            // that choice for them.
            assertThat(service.put(userId, LAPTOP, 1).lines().get(0).issue())
                    .isEqualTo("Out of stock");
        }
    }

    @Nested
    @DisplayName("Changing quantities")
    class Quantities {

        @Test
        @DisplayName("setting the quantity twice does not double it")
        void putSetsRatherThanAdds() {
            service.put(userId, LAPTOP, 2);
            CartResponse basket = service.put(userId, LAPTOP, 2);

            // The difference between PUT and a naive "add": a double tap or a retry would
            // otherwise leave four of something the customer asked for twice.
            assertThat(basket.lines()).hasSize(1);
            assertThat(basket.lines().get(0).quantity()).isEqualTo(2);
        }

        @Test
        @DisplayName("zero removes the line")
        void zeroRemoves() {
            service.put(userId, LAPTOP, 3);

            // What a quantity control set to zero means. Refusing it and making the client call
            // DELETE is a rule the client has to remember rather than one the server honours.
            assertThat(service.put(userId, LAPTOP, 0).lines()).isEmpty();
        }

        @Test
        @DisplayName("an absurd quantity is refused")
        void quantityIsCapped() {
            assertThatThrownBy(() -> service.put(userId, LAPTOP, 1_000_000))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("at most");
        }

        @Test
        @DisplayName("an unknown product cannot be added")
        void unknownProductIsRefused() {
            when(catalogue.lookup(anyList())).thenReturn(Map.of());

            assertThatThrownBy(() -> service.put(userId, HEADSET, 1))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Unknown product");
        }
    }

    @Nested
    @DisplayName("Merging a guest basket")
    class Merging {

        @Test
        @DisplayName("the larger quantity wins, rather than the sum")
        void mergeTakesTheLarger() {
            service.put(userId, LAPTOP, 2);

            CartResponse merged = service.merge(userId, List.of(new CartLineRequest(LAPTOP, 2)));

            // Two on a phone and two on a laptop is one intention expressed twice. Summing to
            // four charges for something nobody asked for; the customer can add more in a click.
            assertThat(merged.lines().get(0).quantity()).isEqualTo(2);
        }

        @Test
        @DisplayName("a guest line for something not already saved is added")
        void mergeAddsNewLines() {
            CartResponse merged = service.merge(userId, List.of(new CartLineRequest(LAPTOP, 3)));

            assertThat(merged.lines()).hasSize(1);
            assertThat(merged.lines().get(0).quantity()).isEqualTo(3);
        }

        @Test
        @DisplayName("a guest line with no quantity is ignored rather than removing anything")
        void mergeIgnoresEmptyLines() {
            service.put(userId, LAPTOP, 2);

            assertThat(service.merge(userId, List.of(new CartLineRequest(LAPTOP, 0)))
                    .lines().get(0).quantity()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("placing an order empties the basket")
    void checkoutEmptiesTheBasket() {
        service.put(userId, LAPTOP, 2);

        service.emptyAfterCheckout(userId);

        // A customer landing back on a basket page still showing what they just bought will
        // buy it again.
        assertThat(cart.getItems()).isEmpty();
    }

    @Test
    @DisplayName("a customer with no basket yet gets an empty one rather than a 404")
    void missingCartIsCreated() {
        UUID newcomer = UUID.randomUUID();
        when(carts.findByUserId(newcomer)).thenReturn(Optional.empty());

        CartResponse basket = service.get(newcomer);

        assertThat(basket.lines()).isEmpty();
        assertThat(basket.checkoutable()).isFalse();
    }
}
