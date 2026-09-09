package com.commerceflow.orderservice.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
import com.commerceflow.orderservice.entity.Order;
import com.commerceflow.orderservice.entity.OrderItem;
import com.commerceflow.orderservice.entity.OrderStatus;

/**
 * The money on an order.
 *
 * <p>Nearly everything here is about a figure being wrong by a small amount rather than an
 * operation failing. Tax rounded in the wrong place, a threshold measured against the wrong
 * number, a rate applied to a list price nobody paid — none of these throw, and none of them are
 * visible without adding the invoice up by hand.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderPricingServiceTest {

    @Mock
    private TaxRateRepository taxRates;

    @Mock
    private ShippingRateRepository shippingRates;

    private OrderPricingService pricing;
    private ShippingQuoteService shipping;

    @BeforeEach
    void setUp() {
        shipping = new ShippingQuoteService(shippingRates);
        pricing = new OrderPricingService(new TaxCalculator(taxRates), shipping);
        when(taxRates.findByCountryCode(anyString())).thenReturn(List.of());
    }

    // =====================================================================================
    // Fixtures
    // =====================================================================================

    private static TaxRate rate(String country, String category, String name, String value) {
        return TaxRate.builder()
                .id(UUID.randomUUID()).countryCode(country).category(category)
                .name(name).rate(new BigDecimal(value)).build();
    }

    private static ShippingRate shippingRate(String country, ShippingMethod method, String base,
            String perItem, String freeAbove) {
        return ShippingRate.builder()
                .id(UUID.randomUUID()).countryCode(country).method(method).currency("EUR")
                .baseAmount(new BigDecimal(base)).perItemAmount(new BigDecimal(perItem))
                .freeAbove(freeAbove == null ? null : new BigDecimal(freeAbove))
                .minDays(1).maxDays(3).active(true).build();
    }

    private static Order order(String country, OrderItem... items) {
        Order order = Order.builder()
                .id(UUID.randomUUID()).orderNumber("CF-20260828-000001")
                .userId(UUID.randomUUID()).userEmail("ada@commerceflow.io")
                .status(OrderStatus.CREATED).currency("EUR").shippingAddress("Keizersgracht 1")
                .shippingCountry(country).items(new ArrayList<>())
                .subtotalAmount(BigDecimal.ZERO).discountTotal(BigDecimal.ZERO)
                .totalAmount(BigDecimal.ZERO).build();
        for (OrderItem item : items) {
            order.addItem(item);
        }
        return order;
    }

    /**
     * @param category the category <em>slug</em>, which is what a tax rate is matched on. The
     *     display name is set alongside it and deliberately differs in case, so that a test
     *     matching on the wrong one of the two fails here rather than in production.
     */
    private static OrderItem line(String category, String listPrice, String discount, int qty) {
        OrderItem item = OrderItem.builder()
                .id(UUID.randomUUID()).productId(UUID.randomUUID()).sku("CF-1")
                .productName("Thing")
                .productCategory(category == null ? null : category.toUpperCase())
                .productCategorySlug(category)
                .quantity(qty)
                .listPrice(new BigDecimal(listPrice)).discountAmount(new BigDecimal(discount))
                .build();
        item.recalculateSubtotal();
        return item;
    }

    // =====================================================================================

    @Nested
    @DisplayName("Tax")
    class Tax {

        @Test
        @DisplayName("is charged on what the customer pays, not on the list price")
        void taxedOnTheDiscountedAmount() {
            when(taxRates.findByCountryCode("NL")).thenReturn(List.of(rate("NL", null, "BTW", "0.2100")));
            Order order = order("NL", line("computers", "100.00", "20.00", 1));

            pricing.price(order, ShippingMethod.PICKUP);

            // 21% of 80, not of 100. Taxing the list price charges tax on money nobody paid,
            // and the customer notices because the arithmetic on their invoice fails.
            assertThat(order.getTaxTotal()).isEqualByComparingTo(new BigDecimal("16.80"));
        }

        @Test
        @DisplayName("a reduced category rate beats the country standard rate")
        void categoryRateWins() {
            when(taxRates.findByCountryCode("NL")).thenReturn(List.of(
                    rate("NL", null, "BTW", "0.2100"),
                    rate("NL", "books", "BTW", "0.0900")));
            Order order = order("NL",
                    line("books", "20.00", "0.00", 1),
                    line("computers", "100.00", "0.00", 1));

            pricing.price(order, ShippingMethod.PICKUP);

            // 1.80 + 21.00. A single order-level rate would have charged 25.20 — wrong by 3.60
            // on a two-line basket, and wrong on most baskets in most of Europe.
            assertThat(order.getTaxTotal()).isEqualByComparingTo(new BigDecimal("22.80"));
            assertThat(order.getItems().get(0).getTaxRate())
                    .isEqualByComparingTo(new BigDecimal("0.0900"));
        }

        @Test
        @DisplayName("is rounded per line and then added up, not the other way round")
        void roundedPerLineThenSummed() {
            when(taxRates.findByCountryCode("NL")).thenReturn(List.of(rate("NL", null, "BTW", "0.2100")));

            // Three lines at 3.33 each. Per line: 0.70 x 3 = 2.10.
            // Taxing the 9.99 total instead gives 2.10 as well here, but the per-line figures are
            // what is printed, and the invariant below is the one a customer can check by hand.
            Order order = order("NL",
                    line("A", "3.33", "0.00", 1),
                    line("B", "3.33", "0.00", 1),
                    line("C", "3.33", "0.00", 1));

            pricing.price(order, ShippingMethod.PICKUP);

            BigDecimal sumOfLines = order.getItems().stream()
                    .map(OrderItem::taxOrZero)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(order.getTaxTotal()).isEqualByComparingTo(sumOfLines);
            order.getItems().forEach(item ->
                    assertThat(item.getTaxAmount().scale()).isLessThanOrEqualTo(2));
        }

        @Test
        @DisplayName("a country with no rates is charged none, and says so on the line")
        void unconfiguredCountryIsUntaxed() {
            Order order = order("AU", line("computers", "100.00", "0.00", 1));

            pricing.price(order, ShippingMethod.PICKUP);

            // Not an error, and not a default rate either. Inventing tax because a table was
            // empty is worse than charging none.
            assertThat(order.getTaxTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(order.getItems().get(0).getTaxName()).isNull();
        }

        @Test
        @DisplayName("the rate and its name are frozen onto the line")
        void rateIsSnapshotted() {
            when(taxRates.findByCountryCode("NL")).thenReturn(List.of(rate("NL", null, "BTW", "0.2100")));
            Order order = order("NL", line("computers", "100.00", "0.00", 1));

            pricing.price(order, ShippingMethod.PICKUP);

            // When a government moves VAT next April this order still explains itself, without
            // anything having to reconstruct historic rates from a table that has moved on.
            OrderItem item = order.getItems().get(0);
            assertThat(item.getTaxName()).isEqualTo("BTW");
            assertThat(item.getTaxRate()).isEqualByComparingTo(new BigDecimal("0.2100"));
        }
    }

    @Nested
    @DisplayName("Delivery")
    class Delivery {

        @Test
        @DisplayName("the free-delivery threshold is measured after discounts")
        void freeThresholdUsesTheDiscountedTotal() {
            when(shippingRates.findByCountryCodeAndMethodAndActiveTrue("NL", ShippingMethod.STANDARD))
                    .thenReturn(Optional.of(shippingRate("NL", ShippingMethod.STANDARD, "4.95", "0", "75.00")));

            // Lists at 80, discounted to 70. The customer is paying 70, which is under the
            // threshold — comparing against the list price would hand out free delivery on a
            // basket that did not earn it, which is the kind of rule customers pass around.
            Order order = order("NL", line("computers", "80.00", "10.00", 1));

            pricing.price(order, ShippingMethod.STANDARD);

            assertThat(order.getShippingAmount()).isEqualByComparingTo(new BigDecimal("4.95"));
        }

        @Test
        @DisplayName("clears the threshold and delivery costs nothing")
        void aboveThresholdIsFree() {
            when(shippingRates.findByCountryCodeAndMethodAndActiveTrue("NL", ShippingMethod.STANDARD))
                    .thenReturn(Optional.of(shippingRate("NL", ShippingMethod.STANDARD, "4.95", "0", "75.00")));
            Order order = order("NL", line("computers", "80.00", "0.00", 1));

            ShippingQuote quote = pricing.price(order, ShippingMethod.STANDARD);

            assertThat(quote.free()).isTrue();
            assertThat(order.getShippingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("an unlisted country falls back to the rest-of-world rate rather than failing")
        void unlistedCountryUsesTheWildcard() {
            when(shippingRates.findByCountryCodeAndMethodAndActiveTrue("CL", ShippingMethod.STANDARD))
                    .thenReturn(Optional.empty());
            when(shippingRates.findByCountryCodeAndMethodAndActiveTrue("*", ShippingMethod.STANDARD))
                    .thenReturn(Optional.of(shippingRate("*", ShippingMethod.STANDARD, "19.95", "1.50", null)));

            Order order = order("CL", line("computers", "50.00", "0.00", 2));
            pricing.price(order, ShippingMethod.STANDARD);

            // 19.95 + 2 x 1.50. A shop that cannot price delivery to Chile should say what it
            // costs, not reject the order at the last step of checkout.
            assertThat(order.getShippingAmount()).isEqualByComparingTo(new BigDecimal("22.95"));
        }

        @Test
        @DisplayName("a method with no rate anywhere is refused rather than invented")
        void unpricableMethodIsRefused() {
            when(shippingRates.findByCountryCodeAndMethodAndActiveTrue(anyString(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Optional.empty());
            Order order = order("CL", line("computers", "50.00", "0.00", 1));

            assertThatThrownBy(() -> pricing.price(order, ShippingMethod.EXPRESS))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("EXPRESS");
        }

        @Test
        @DisplayName("collection is free without a rate row existing at all")
        void pickupNeedsNoRate() {
            Order order = order("NL", line("computers", "50.00", "0.00", 1));

            ShippingQuote quote = pricing.price(order, ShippingMethod.PICKUP);

            // No row, on purpose: a "free" rate row is a row somebody can later edit into a
            // charge for walking to a shop.
            assertThat(quote.free()).isTrue();
            assertThat(order.getShippingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("no method means standard, rather than nothing")
        void nullMethodDefaults() {
            when(shippingRates.findByCountryCodeAndMethodAndActiveTrue("NL", ShippingMethod.STANDARD))
                    .thenReturn(Optional.of(shippingRate("NL", ShippingMethod.STANDARD, "4.95", "0", null)));
            Order order = order("NL", line("computers", "50.00", "0.00", 1));

            assertThat(pricing.price(order, null).method()).isEqualTo(ShippingMethod.STANDARD);
            assertThat(order.getShippingMethod()).isEqualTo(ShippingMethod.STANDARD);
        }

        @Test
        @DisplayName("the quoted window is kept on the order, not recomputed later")
        void deliveryWindowIsStored() {
            when(shippingRates.findByCountryCodeAndMethodAndActiveTrue("NL", ShippingMethod.STANDARD))
                    .thenReturn(Optional.of(shippingRate("NL", ShippingMethod.STANDARD, "4.95", "0", null)));
            Order order = order("NL", line("computers", "50.00", "0.00", 1));

            pricing.price(order, ShippingMethod.STANDARD);

            // What the customer was told when they agreed to pay. Re-deriving it from today's
            // rate card would change the promise after the fact.
            assertThat(order.getDeliveryMinDays()).isEqualTo(1);
            assertThat(order.getDeliveryMaxDays()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("The total")
    class Total {

        @Test
        @DisplayName("is goods minus discount plus tax plus delivery, and nothing else")
        void totalIsTheSumOfItsParts() {
            when(taxRates.findByCountryCode("NL")).thenReturn(List.of(rate("NL", null, "BTW", "0.2100")));
            when(shippingRates.findByCountryCodeAndMethodAndActiveTrue("NL", ShippingMethod.STANDARD))
                    .thenReturn(Optional.of(shippingRate("NL", ShippingMethod.STANDARD, "4.95", "0", "75.00")));

            Order order = order("NL", line("computers", "50.00", "10.00", 1));
            pricing.price(order, ShippingMethod.STANDARD);

            // 50.00 goods, 10.00 off, 8.40 tax on the 40.00 paid, 4.95 delivery.
            assertThat(order.getSubtotalAmount()).isEqualByComparingTo(new BigDecimal("50.00"));
            assertThat(order.getDiscountTotal()).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(order.getTaxTotal()).isEqualByComparingTo(new BigDecimal("8.40"));
            assertThat(order.getShippingAmount()).isEqualByComparingTo(new BigDecimal("4.95"));
            assertThat(order.getTotalAmount()).isEqualByComparingTo(new BigDecimal("53.35"));
        }

        @Test
        @DisplayName("is expressible in its currency")
        void totalIsRoundedToTheCurrency() {
            when(taxRates.findByCountryCode("NL")).thenReturn(List.of(rate("NL", null, "BTW", "0.2100")));
            Order order = order("NL", line("computers", "19.99", "0.00", 3));

            pricing.price(order, ShippingMethod.PICKUP);

            // A total a payment gateway cannot charge is a total that gets silently rounded by
            // somebody else, and then the order and the charge disagree.
            assertThat(order.getTotalAmount().scale()).isLessThanOrEqualTo(2);
        }
    }
}
