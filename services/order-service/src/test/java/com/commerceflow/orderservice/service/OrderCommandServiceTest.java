package com.commerceflow.orderservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.commerceflow.common.event.DomainEvent;
import com.commerceflow.common.event.OrderCreatedEvent;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.outbox.OutboxService;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.orderservice.config.OrderProperties;
import com.commerceflow.orderservice.dto.CatalogProduct;
import com.commerceflow.common.address.PostalAddress;
import com.commerceflow.orderservice.dto.CreateOrderRequest;
import com.commerceflow.orderservice.dto.OrderItemRequest;
import com.commerceflow.orderservice.dto.OrderResponse;
import com.commerceflow.orderservice.entity.Order;
import com.commerceflow.orderservice.entity.OrderItem;
import com.commerceflow.orderservice.entity.OrderStatus;
import com.commerceflow.orderservice.mapper.OrderMapper;
import com.commerceflow.orderservice.pricing.OrderPricingService;
import com.commerceflow.orderservice.pricing.ShippingMethod;
import com.commerceflow.orderservice.pricing.ShippingQuote;
import com.commerceflow.orderservice.cart.CartService;
import com.commerceflow.orderservice.promotion.CouponService;
import com.commerceflow.orderservice.repository.OrderRepository;
import com.commerceflow.orderservice.saga.OrderSagaOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderCommandServiceTest {

    private static final UUID LAPTOP = UUID.randomUUID();
    private static final UUID HEADSET = UUID.randomUUID();

    private static final AuthenticatedUser CUSTOMER = new AuthenticatedUser(
            UUID.randomUUID(), "ada@commerceflow.io", Set.of("CUSTOMER"), UUID.randomUUID().toString());

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderNumberGenerator orderNumberGenerator;

    @Mock
    private ProductCatalogClient catalogClient;

    @Mock
    private OrderProjectionService projectionService;

    @Mock
    private OutboxService outboxService;

    /**
     * The saga is started inside {@code placeOrder}'s transaction. Mocked here because this test
     * is about pricing and validation; the orchestrator has its own tests.
     */
    @Mock
    private OrderSagaOrchestrator orchestrator;

    @Captor
    private ArgumentCaptor<DomainEvent> eventCaptor;

    @Mock
    private OrderPricingService pricingService;

    @Mock
    private CouponService couponService;

    @Mock
    private CartService cartService;

    @Captor
    private ArgumentCaptor<Order> orderCaptor;

    private OrderCommandService service;

    /**
     * A destination, structured. The country code matters even here: it is what a real
     * {@code OrderPricingService} would use to pick a tax rate.
     */
    private static final PostalAddress AMSTERDAM = new PostalAddress("Ada Lovelace", null,
            "Keizersgracht 1", null, "Amsterdam", null, "1015 CJ", "NL");

    @BeforeEach
    void setUp() {
        OrderMapper mapper = new OrderMapper(new ObjectMapper().registerModule(new JavaTimeModule()));
        OrderProperties properties = new OrderProperties();

        service = new OrderCommandService(orderRepository, orderNumberGenerator, catalogClient,
                projectionService, pricingService, couponService, cartService, orchestrator,
                outboxService, mapper, properties);

        // Pricing is stubbed to charge nothing for tax or delivery, so the assertions below stay
        // about the snapshot and the line arithmetic. What tax and delivery do to a total is
        // pinned in OrderPricingServiceTest, where a wrong answer is the point of the test rather
        // than noise in an unrelated one.
        when(pricingService.price(any(Order.class), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(call -> {
            Order order = call.getArgument(0);
            order.recalculateTotals();
            return new ShippingQuote(ShippingMethod.STANDARD, BigDecimal.ZERO, order.getCurrency(),
                    1, 2, true, "Free delivery, 1-2 working days");
        });

        when(orderNumberGenerator.next()).thenReturn("CF-20260826-000001");
        when(orderRepository.save(any(Order.class))).thenAnswer(call -> call.getArgument(0));
        when(catalogClient.lookup(anyList())).thenReturn(Map.of(
                LAPTOP, new CatalogProduct(LAPTOP, "CF-LAPTOP-001", "Developer Laptop 14",
                        "Computers", "computers", null, new BigDecimal("1899.00"), "EUR", true, 50),
                HEADSET, new CatalogProduct(HEADSET, "CF-HEADSET-001", "Noise Cancelling Headset",
                        "Computers", "computers", null, new BigDecimal("279.00"), "EUR", true, 80)));
    }

    @Test
    @DisplayName("the order line copies the catalogue, it does not reference it")
    void snapshotsEverythingNeededToRenderTheOrderForever() {
        service.placeOrder(new CreateOrderRequest(
                List.of(new OrderItemRequest(LAPTOP, 2)), AMSTERDAM, null, null, null),
                CUSTOMER);

        verify(orderRepository).save(orderCaptor.capture());
        OrderItem line = orderCaptor.getValue().getItems().get(0);

        // Everything the order will ever need to describe itself, taken once. Reading any of
        // this back from the catalogue later would let a rename or a re-shot photo rewrite what
        // the customer saw.
        assertThat(line.getProductName()).isEqualTo("Developer Laptop 14");
        assertThat(line.getSku()).isEqualTo("CF-LAPTOP-001");
        assertThat(line.getProductCategory()).isEqualTo("Computers");

        // The slug as well as the name. They are used for different things: the name goes on the
        // invoice, the slug is what the tax rate was matched against. Keeping only the name would
        // mean a renamed section silently changed the tax on everything inside it.
        assertThat(line.getProductCategorySlug()).isEqualTo("computers");
        assertThat(line.getListPrice()).isEqualByComparingTo(new BigDecimal("1899.00"));
    }

    @Test
    @DisplayName("the line arithmetic adds up: list minus discount, times quantity")
    void lineArithmeticHolds() {
        service.placeOrder(new CreateOrderRequest(
                List.of(new OrderItemRequest(LAPTOP, 3)), AMSTERDAM, null, null, null),
                CUSTOMER);

        verify(orderRepository).save(orderCaptor.capture());
        OrderItem line = orderCaptor.getValue().getItems().get(0);

        // No promotion engine yet, so nothing comes off — but the invariant is what a future one
        // will have to keep, and an invoice whose numbers do not add up cannot be defended.
        assertThat(line.getDiscountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(line.getUnitPrice())
                .isEqualByComparingTo(line.getListPrice().subtract(line.getDiscountAmount()));
        assertThat(line.getSubtotal())
                .isEqualByComparingTo(line.getUnitPrice().multiply(new BigDecimal("3")));
    }

    @Test
    @DisplayName("the order records what it listed at, what came off, and what was charged")
    void orderTotalsAreFrozen() {
        service.placeOrder(new CreateOrderRequest(
                List.of(new OrderItemRequest(LAPTOP, 2), new OrderItemRequest(HEADSET, 1)),
                AMSTERDAM, null, null, null), CUSTOMER);

        verify(orderRepository).save(orderCaptor.capture());
        Order saved = orderCaptor.getValue();

        // 2 x 1899.00 + 1 x 279.00
        assertThat(saved.getSubtotalAmount()).isEqualByComparingTo(new BigDecimal("4077.00"));
        assertThat(saved.getDiscountTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(saved.getTotalAmount())
                .isEqualByComparingTo(saved.getSubtotalAmount()
                        .subtract(saved.getDiscountTotal())
                        .add(saved.getTaxTotal())
                        .add(saved.getShippingAmount()));
    }

    @Test
    @DisplayName("prices the basket from the catalogue and totals the lines")
    void pricesBasketFromCatalogue() {
        OrderResponse response = service.placeOrder(new CreateOrderRequest(
                List.of(new OrderItemRequest(LAPTOP, 2), new OrderItemRequest(HEADSET, 1)),
                AMSTERDAM, null, null, null), CUSTOMER);

        // 2 x 1899.00 + 1 x 279.00
        assertThat(response.totalAmount()).isEqualByComparingTo(new BigDecimal("4077.00"));
        assertThat(response.currency()).isEqualTo("EUR");
        assertThat(response.status()).isEqualTo(OrderStatus.CREATED.name());
        assertThat(response.itemCount()).isEqualTo(3);
        assertThat(response.items()).hasSize(2);
        assertThat(response.userId()).isEqualTo(CUSTOMER.userId());
    }

    @Test
    @DisplayName("writes the aggregate, the projection and the outbox row in one transaction")
    void writesAggregateProjectionAndOutboxTogether() {
        service.placeOrder(new CreateOrderRequest(
                List.of(new OrderItemRequest(LAPTOP, 1)), AMSTERDAM, null, null, null), CUSTOMER);

        verify(orderRepository).save(orderCaptor.capture());
        verify(projectionService).project(any(Order.class));
        verify(outboxService).append(anyString(), anyString(), eventCaptor.capture());

        assertThat(eventCaptor.getValue()).isInstanceOf(OrderCreatedEvent.class);
        OrderCreatedEvent event = (OrderCreatedEvent) eventCaptor.getValue();
        Order saved = orderCaptor.getValue();

        assertThat(event.getOrderId()).isEqualTo(saved.getId());
        assertThat(event.getOrderNumber()).isEqualTo("CF-20260826-000001");
        assertThat(event.getTotalAmount()).isEqualByComparingTo(new BigDecimal("1899.00"));
        assertThat(event.getUserEmail()).isEqualTo(CUSTOMER.email());
        // The saga participants must not have to call back for the line items.
        assertThat(event.getItems()).hasSize(1);
        assertThat(event.getItems().get(0).getSku()).isEqualTo("CF-LAPTOP-001");
    }

    @Test
    @DisplayName("snapshots the catalogue name and price onto the order line")
    void snapshotsCatalogueData() {
        service.placeOrder(new CreateOrderRequest(
                List.of(new OrderItemRequest(LAPTOP, 1)), AMSTERDAM, null, null, null), CUSTOMER);

        verify(orderRepository).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getItems().get(0).getProductName())
                .isEqualTo("Developer Laptop 14");
        assertThat(orderCaptor.getValue().getItems().get(0).getUnitPrice())
                .isEqualByComparingTo(new BigDecimal("1899.00"));
    }

    @Test
    @DisplayName("an unknown product is rejected before anything is written")
    void rejectsUnknownProduct() {
        when(catalogClient.lookup(anyList())).thenReturn(Map.of());

        assertThatThrownBy(() -> service.placeOrder(new CreateOrderRequest(
                List.of(new OrderItemRequest(LAPTOP, 1)), AMSTERDAM, null, null, null), CUSTOMER))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);

        verify(orderRepository, never()).save(any(Order.class));
        verify(outboxService, never()).append(anyString(), anyString(), any(DomainEvent.class));
    }

    @Test
    @DisplayName("a deactivated product cannot be ordered")
    void rejectsInactiveProduct() {
        when(catalogClient.lookup(anyList())).thenReturn(Map.of(
                LAPTOP, new CatalogProduct(LAPTOP, "CF-LAPTOP-001", "Developer Laptop 14",
                        "Computers", "computers", null, new BigDecimal("1899.00"), "EUR", false, 50)));

        assertThatThrownBy(() -> service.placeOrder(new CreateOrderRequest(
                List.of(new OrderItemRequest(LAPTOP, 1)), AMSTERDAM, null, null, null), CUSTOMER))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("an order may not mix currencies")
    void rejectsMixedCurrencies() {
        when(catalogClient.lookup(anyList())).thenReturn(Map.of(
                LAPTOP, new CatalogProduct(LAPTOP, "CF-LAPTOP-001", "Laptop",
                        "Computers", "computers", null, new BigDecimal("1899.00"), "EUR", true, 50),
                HEADSET, new CatalogProduct(HEADSET, "CF-HEADSET-001", "Headset",
                        "Computers", "computers", null, new BigDecimal("279.00"), "USD", true, 80)));

        assertThatThrownBy(() -> service.placeOrder(new CreateOrderRequest(
                List.of(new OrderItemRequest(LAPTOP, 1), new OrderItemRequest(HEADSET, 1)),
                AMSTERDAM, null, null, null), CUSTOMER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("mix currencies");
    }

    @Test
    @DisplayName("an empty basket is rejected")
    void rejectsEmptyBasket() {
        assertThatThrownBy(() -> service.placeOrder(
                new CreateOrderRequest(List.of(), AMSTERDAM, null, null, null), CUSTOMER))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.EMPTY_ORDER);
    }

    @Test
    @DisplayName("replaying an idempotency key returns the original order instead of a second one")
    void idempotentReplayReturnsOriginalOrder() {
        Order original = Order.builder()
                .id(UUID.randomUUID())
                .orderNumber("CF-20260826-000001")
                .userId(CUSTOMER.userId())
                .userEmail(CUSTOMER.email())
                .status(OrderStatus.INVENTORY_RESERVED)
                .totalAmount(new BigDecimal("1899.00"))
                .currency("EUR")
                .shippingAddress(AMSTERDAM.formatted())
                .idempotencyKey("key-1")
                .items(new java.util.ArrayList<>())
                .build();
        when(orderRepository.findByUserIdAndIdempotencyKey(CUSTOMER.userId(), "key-1"))
                .thenReturn(Optional.of(original));

        OrderResponse response = service.placeOrder(new CreateOrderRequest(
                List.of(new OrderItemRequest(LAPTOP, 1)), AMSTERDAM, null, null, "key-1"), CUSTOMER);

        assertThat(response.id()).isEqualTo(original.getId());
        verify(orderRepository, never()).save(any(Order.class));
        verify(outboxService, never()).append(anyString(), anyString(), any(DomainEvent.class));
        verify(catalogClient, never()).lookup(anyList());
    }

    @Test
    @DisplayName("a catalogue outage rejects the order rather than accepting an unpriced one")
    void catalogueOutageRejectsTheOrder() {
        when(catalogClient.lookup(anyList()))
                .thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "catalogue down"));

        assertThatThrownBy(() -> service.placeOrder(new CreateOrderRequest(
                List.of(new OrderItemRequest(LAPTOP, 1)), AMSTERDAM, null, null, null), CUSTOMER))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);

        verify(orderRepository, never()).save(any(Order.class));
    }
}
