package com.commerceflow.orderservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.commerceflow.common.constant.KafkaTopics;
import com.commerceflow.common.event.ConfirmInventoryCommand;
import com.commerceflow.common.event.InventoryConfirmedEvent;
import com.commerceflow.common.event.InventoryFailedEvent;
import com.commerceflow.common.event.InventoryReleasedEvent;
import com.commerceflow.common.event.InventoryReservedEvent;
import com.commerceflow.common.event.NotificationSendEvent;
import com.commerceflow.common.event.NotificationSentEvent;
import com.commerceflow.common.event.OrderCancelledEvent;
import com.commerceflow.common.event.OrderCompletedEvent;
import com.commerceflow.common.event.PaymentCompletedEvent;
import com.commerceflow.common.event.PaymentFailedEvent;
import com.commerceflow.common.event.ProcessPaymentCommand;
import com.commerceflow.common.event.ReleaseInventoryCommand;
import com.commerceflow.common.event.ReserveInventoryCommand;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.common.testsupport.AbstractSagaIntegrationTest;
import com.commerceflow.orderservice.dto.CatalogProduct;
import com.commerceflow.common.address.PostalAddress;
import com.commerceflow.orderservice.dto.CreateOrderRequest;
import com.commerceflow.orderservice.dto.OrderItemRequest;
import com.commerceflow.orderservice.dto.OrderResponse;
import com.commerceflow.orderservice.entity.Order;
import com.commerceflow.orderservice.entity.OrderStatus;
import com.commerceflow.orderservice.repository.OrderRepository;
import com.commerceflow.orderservice.repository.OrderViewRepository;
import com.commerceflow.orderservice.saga.entity.SagaState;
import com.commerceflow.orderservice.saga.entity.SagaStep;
import com.commerceflow.orderservice.saga.entity.SagaStepLog;
import com.commerceflow.orderservice.saga.entity.StepStatus;
import com.commerceflow.orderservice.saga.repository.SagaInstanceRepository;
import com.commerceflow.orderservice.saga.repository.SagaStepLogRepository;
import com.commerceflow.orderservice.service.OrderCommandService;
import com.commerceflow.orderservice.service.ProductCatalogClient;

/**
 * The orchestrator, driven end to end over real Kafka and PostgreSQL.
 *
 * <p>The test plays every participant. It waits for the command the orchestrator issues, answers
 * it the way the real service would, and asserts on the command that follows. That is the whole
 * pattern under test: what the coordinator decides, given what it is told.
 *
 * <p>Because the flow is an object rather than an emergent property of four subscriptions, it can
 * be walked one step at a time and asserted on at every point — including the saga row and the
 * step log, which say what the orchestrator believed at each stage.
 *
 * <p>The catalogue is the one stubbed collaborator: it is a synchronous HTTP dependency on
 * another service, and standing it up would be testing Inventory, which its own integration test
 * already does.
 */
class OrderSagaIntegrationTest extends AbstractSagaIntegrationTest {

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final BigDecimal UNIT_PRICE = new BigDecimal("1899.00");

    private static final AuthenticatedUser CUSTOMER = new AuthenticatedUser(
            UUID.randomUUID(), "ada@commerceflow.io", Set.of("CUSTOMER"),
            UUID.randomUUID().toString());

    @MockitoBean
    private ProductCatalogClient catalogClient;

    @Autowired
    private OrderCommandService commandService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderViewRepository orderViewRepository;

    @Autowired
    private SagaInstanceRepository sagaRepository;

    @Autowired
    private SagaStepLogRepository stepLogRepository;

    @BeforeEach
    void stubCatalogue() {
        when(catalogClient.lookup(anyList())).thenReturn(Map.of(
                PRODUCT_ID, new CatalogProduct(PRODUCT_ID, "CF-LAPTOP-001", "CommerceFlow Developer Laptop 14",
                        "Computers", "computers", null, UNIT_PRICE, "EUR", true, 50)));
    }

    // =====================================================================================
    // Start
    // =====================================================================================

    @Test
    @DisplayName("placing an order opens a saga and commands Inventory to reserve")
    void placingAnOrderIssuesTheFirstCommand() {
        try (Consumer<String, Object> commands = consumerFor(KafkaTopics.INVENTORY_COMMANDS)) {
            OrderResponse order = placeOrder(2, null);

            ReserveInventoryCommand command =
                    awaitMessage(commands, ReserveInventoryCommand.class, SAGA_TIMEOUT);

            assertThat(command.getOrderId()).isEqualTo(order.id());
            assertThat(command.getSagaId()).isEqualTo(order.id());
            assertThat(command.getAttempt()).isEqualTo(1);
            assertThat(command.getTotalAmount()).isEqualByComparingTo(new BigDecimal("3798.00"));
            assertThat(command.getItems()).hasSize(1);
            assertThat(command.getItems().get(0).getSku()).isEqualTo("CF-LAPTOP-001");

            // The saga row and the order are written in the same transaction, so by the time the
            // command is on the bus the saga must already be waiting for its reply.
            var saga = sagaRepository.findById(order.id()).orElseThrow();
            assertThat(saga.getState()).isEqualTo(SagaState.STARTED);
            assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.RESERVE_INVENTORY);
            assertThat(saga.getCurrentCommandId()).isEqualTo(command.getEventId());
            assertThat(saga.getStepDeadline()).isNotNull();
        }
    }

    @Test
    @DisplayName("order.created is still announced, even though no participant consumes it")
    void orderCreatedIsStillPublishedForObservers() {
        try (Consumer<String, Object> observers = consumerFor(KafkaTopics.ORDER_CREATED)) {
            OrderResponse order = placeOrder(1, null);

            var event = awaitMessage(observers,
                    com.commerceflow.common.event.OrderCreatedEvent.class, SAGA_TIMEOUT);

            assertThat(event.getOrderId()).isEqualTo(order.id());
            assertThat(event.getTotalAmount()).isEqualByComparingTo(UNIT_PRICE);
        }
    }

    // =====================================================================================
    // The happy path, one step at a time
    // =====================================================================================

    @Test
    @DisplayName("the orchestrator walks reserve, pay, confirm and notify to COMPLETED")
    void happyPathRunsEveryStepInOrder() {
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        try (Consumer<String, Object> bus = consumerFor(
                KafkaTopics.INVENTORY_COMMANDS, KafkaTopics.PAYMENT_COMMANDS,
                KafkaTopics.NOTIFICATION_SEND, KafkaTopics.ORDER_COMPLETED)) {

            OrderResponse order = placeOrder(1, null);

            // ---- step 1: reserve ------------------------------------------------------------
            ReserveInventoryCommand reserve =
                    awaitMessage(bus, ReserveInventoryCommand.class, SAGA_TIMEOUT);
            reply(KafkaTopics.INVENTORY_RESERVED, InventoryReservedEvent.builder()
                    .orderId(order.id())
                    .reservationId(reservationId)
                    .status("RESERVED")
                    .build(), reserve);

            // ---- step 2: pay ----------------------------------------------------------------
            ProcessPaymentCommand pay =
                    awaitMessage(bus, ProcessPaymentCommand.class, SAGA_TIMEOUT);
            assertThat(pay.getSagaId()).isEqualTo(order.id());
            assertThat(pay.getAmount()).isEqualByComparingTo(UNIT_PRICE);
            // The reservation learned from step 1 is carried forward, so compensation never has
            // to ask Inventory what it is holding.
            assertThat(pay.getReservationId()).isEqualTo(reservationId);

            await().atMost(SAGA_TIMEOUT).untilAsserted(() ->
                    assertThat(status(order.id())).isEqualTo(OrderStatus.INVENTORY_RESERVED));

            reply(KafkaTopics.PAYMENT_COMPLETED, PaymentCompletedEvent.builder()
                    .orderId(order.id())
                    .paymentId(paymentId)
                    .amount(UNIT_PRICE)
                    .currency("EUR")
                    .status("COMPLETED")
                    .build(), pay);

            // ---- step 3: confirm ------------------------------------------------------------
            ConfirmInventoryCommand confirm =
                    awaitMessage(bus, ConfirmInventoryCommand.class, SAGA_TIMEOUT);
            assertThat(confirm.getOrderId()).isEqualTo(order.id());

            await().atMost(SAGA_TIMEOUT).untilAsserted(() ->
                    assertThat(status(order.id())).isEqualTo(OrderStatus.PAID));

            reply(KafkaTopics.INVENTORY_CONFIRMED, InventoryConfirmedEvent.builder()
                    .orderId(order.id())
                    .reservationId(reservationId)
                    .build(), confirm);

            // ---- the order completes, and only now ------------------------------------------
            OrderCompletedEvent completed =
                    awaitMessage(bus, OrderCompletedEvent.class, SAGA_TIMEOUT);
            assertThat(completed.getPaymentId()).isEqualTo(paymentId);

            // ---- step 4: notify -------------------------------------------------------------
            NotificationSendEvent notify =
                    awaitMessage(bus, NotificationSendEvent.class, SAGA_TIMEOUT);
            assertThat(notify.getTemplateCode()).isEqualTo("ORDER_CONFIRMED");
            assertThat(notify.getRecipient()).isEqualTo(CUSTOMER.email());
            assertThat(notify.getParams()).containsEntry("orderNumber", order.orderNumber());

            reply(KafkaTopics.NOTIFICATION_SENT, NotificationSentEvent.builder()
                    .notificationId(UUID.randomUUID())
                    .referenceId(order.id())
                    .status("SENT")
                    .build(), notify);

            // ---- and the saga closes --------------------------------------------------------
            await().atMost(SAGA_TIMEOUT).untilAsserted(() -> {
                var saga = sagaRepository.findById(order.id()).orElseThrow();
                assertThat(saga.getState()).isEqualTo(SagaState.COMPLETED);
                assertThat(saga.getCurrentStep()).isNull();
                assertThat(saga.getCompletedAt()).isNotNull();

                assertThat(status(order.id())).isEqualTo(OrderStatus.COMPLETED);
                // Projected in the same transaction, so it can never lag behind the aggregate.
                assertThat(orderViewRepository.findById(order.id()).orElseThrow().getStatus())
                        .isEqualTo(OrderStatus.COMPLETED);
            });

            assertStepLog(order.id(),
                    SagaStep.RESERVE_INVENTORY, SagaStep.PROCESS_PAYMENT,
                    SagaStep.CONFIRM_INVENTORY, SagaStep.NOTIFY_CUSTOMER);
        }
    }

    // =====================================================================================
    // Compensation
    // =====================================================================================

    @Test
    @DisplayName("a reserve failure cancels the order and compensates nothing, because nothing moved")
    void inventoryFailureNeedsNoCompensation() {
        try (Consumer<String, Object> bus = consumerFor(
                KafkaTopics.INVENTORY_COMMANDS, KafkaTopics.NOTIFICATION_SEND,
                KafkaTopics.ORDER_CANCELLED)) {

            OrderResponse order = placeOrder(1, null);
            ReserveInventoryCommand reserve =
                    awaitMessage(bus, ReserveInventoryCommand.class, SAGA_TIMEOUT);

            reply(KafkaTopics.INVENTORY_FAILED, InventoryFailedEvent.builder()
                    .orderId(order.id())
                    .reason("OUT_OF_STOCK")
                    .unavailableSkus(List.of("CF-LAPTOP-001"))
                    .build(), reserve);

            OrderCancelledEvent cancelled =
                    awaitMessage(bus, OrderCancelledEvent.class, SAGA_TIMEOUT);
            // The published API value, not the orchestrator's internal step name.
            assertThat(cancelled.getFailedStep()).isEqualTo("INVENTORY");
            assertThat(cancelled.getReason()).isEqualTo("OUT_OF_STOCK");

            // Straight to the notification: there is no reservation to release.
            NotificationSendEvent notify =
                    awaitMessage(bus, NotificationSendEvent.class, SAGA_TIMEOUT);
            assertThat(notify.getTemplateCode()).isEqualTo("ORDER_CANCELLED");
            assertThat(notify.getParams()).containsEntry("reason", "out of stock");

            await().atMost(SAGA_TIMEOUT).untilAsserted(() -> {
                Order stored = orderRepository.findById(order.id()).orElseThrow();
                assertThat(stored.getStatus()).isEqualTo(OrderStatus.CANCELLED);
                assertThat(stored.getFailureReason()).isEqualTo("OUT_OF_STOCK");
            });

            assertThat(stepsOf(order.id()))
                    .doesNotContain(SagaStep.RELEASE_INVENTORY)
                    .containsExactly(SagaStep.RESERVE_INVENTORY, SagaStep.NOTIFY_CUSTOMER);
        }
    }

    @Test
    @DisplayName("a declined payment releases the reservation, then tells the customer")
    void paymentFailureCompensatesInventoryFirst() {
        UUID reservationId = UUID.randomUUID();

        try (Consumer<String, Object> bus = consumerFor(
                KafkaTopics.INVENTORY_COMMANDS, KafkaTopics.PAYMENT_COMMANDS,
                KafkaTopics.NOTIFICATION_SEND)) {

            OrderResponse order = placeOrder(6, null);

            ReserveInventoryCommand reserve =
                    awaitMessage(bus, ReserveInventoryCommand.class, SAGA_TIMEOUT);
            reply(KafkaTopics.INVENTORY_RESERVED, InventoryReservedEvent.builder()
                    .orderId(order.id())
                    .reservationId(reservationId)
                    .status("RESERVED")
                    .build(), reserve);

            ProcessPaymentCommand pay =
                    awaitMessage(bus, ProcessPaymentCommand.class, SAGA_TIMEOUT);
            reply(KafkaTopics.PAYMENT_FAILED, PaymentFailedEvent.builder()
                    .orderId(order.id())
                    .paymentId(UUID.randomUUID())
                    .reason("INSUFFICIENT_FUNDS")
                    .build(), pay);

            // The compensation is commanded, not inferred by Inventory from something it overheard.
            ReleaseInventoryCommand release =
                    awaitMessage(bus, ReleaseInventoryCommand.class, SAGA_TIMEOUT);
            assertThat(release.getOrderId()).isEqualTo(order.id());
            assertThat(release.getReason()).isEqualTo("PAYMENT_FAILED");

            // The customer is told the order failed as soon as it is known, not after the cleanup.
            await().atMost(SAGA_TIMEOUT).untilAsserted(() ->
                    assertThat(status(order.id())).isEqualTo(OrderStatus.CANCELLED));

            reply(KafkaTopics.INVENTORY_RELEASED, InventoryReleasedEvent.builder()
                    .orderId(order.id())
                    .reservationId(reservationId)
                    .reason("PAYMENT_FAILED")
                    .build(), release);

            NotificationSendEvent notify =
                    awaitMessage(bus, NotificationSendEvent.class, SAGA_TIMEOUT);
            assertThat(notify.getTemplateCode()).isEqualTo("ORDER_CANCELLED");
            assertThat(notify.getParams()).containsEntry("failedStep", "payment");

            reply(KafkaTopics.NOTIFICATION_SENT, NotificationSentEvent.builder()
                    .notificationId(UUID.randomUUID())
                    .referenceId(order.id())
                    .status("SENT")
                    .build(), notify);

            await().atMost(SAGA_TIMEOUT).untilAsserted(() -> {
                var saga = sagaRepository.findById(order.id()).orElseThrow();
                assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATED);
                assertThat(saga.getFailedStep()).isEqualTo(SagaStep.PROCESS_PAYMENT);
                assertThat(saga.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
            });

            assertStepLog(order.id(),
                    SagaStep.RESERVE_INVENTORY, SagaStep.PROCESS_PAYMENT,
                    SagaStep.RELEASE_INVENTORY, SagaStep.NOTIFY_CUSTOMER);
        }
    }

    // =====================================================================================
    // The guard that makes redelivery safe
    // =====================================================================================

    @Test
    @DisplayName("a reply for a step the saga has moved past cannot change the order")
    void supersededReplyIsIgnored() {
        UUID reservationId = UUID.randomUUID();

        try (Consumer<String, Object> bus = consumerFor(
                KafkaTopics.INVENTORY_COMMANDS, KafkaTopics.PAYMENT_COMMANDS)) {

            OrderResponse order = placeOrder(1, null);
            ReserveInventoryCommand reserve =
                    awaitMessage(bus, ReserveInventoryCommand.class, SAGA_TIMEOUT);
            reply(KafkaTopics.INVENTORY_RESERVED, InventoryReservedEvent.builder()
                    .orderId(order.id())
                    .reservationId(reservationId)
                    .status("RESERVED")
                    .build(), reserve);

            awaitMessage(bus, ProcessPaymentCommand.class, SAGA_TIMEOUT);
            await().atMost(SAGA_TIMEOUT).untilAsserted(() ->
                    assertThat(status(order.id())).isEqualTo(OrderStatus.INVENTORY_RESERVED));

            // An inventory.failed arriving now answers a step that is already closed. Acting on
            // it would cancel an order whose stock is held and whose payment is in flight.
            reply(KafkaTopics.INVENTORY_FAILED, InventoryFailedEvent.builder()
                    .orderId(order.id())
                    .reason("OUT_OF_STOCK")
                    .build(), order.id());

            await().during(java.time.Duration.ofSeconds(3)).atMost(SAGA_TIMEOUT)
                    .untilAsserted(() -> {
                        assertThat(status(order.id())).isEqualTo(OrderStatus.INVENTORY_RESERVED);
                        assertThat(sagaRepository.findById(order.id()).orElseThrow()
                                .getCurrentStep()).isEqualTo(SagaStep.PROCESS_PAYMENT);
                    });
        }
    }

    @Test
    @DisplayName("a reply with no sagaId belongs to another flow and is ignored")
    void replyWithoutSagaIdIsIgnored() {
        OrderResponse order = placeOrder(1, null);

        // This is what Auth Service's welcome mail looks like on notification.sent.
        publish(KafkaTopics.NOTIFICATION_SENT, NotificationSentEvent.builder()
                .notificationId(UUID.randomUUID())
                .referenceId(UUID.randomUUID())
                .status("SENT")
                .build());

        await().during(java.time.Duration.ofSeconds(3)).atMost(SAGA_TIMEOUT)
                .untilAsserted(() -> assertThat(sagaRepository.findById(order.id()).orElseThrow()
                        .getState()).isEqualTo(SagaState.STARTED));
    }

    @Test
    @DisplayName("replaying an idempotency key returns the original order, not a second saga")
    void idempotentCreateDoesNotDuplicate() {
        String key = "it-" + UUID.randomUUID();

        OrderResponse first = placeOrder(1, key);
        OrderResponse second = placeOrder(1, key);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(sagaRepository.findAll().stream()
                .filter(saga -> saga.getId().equals(first.id()))
                .count()).isEqualTo(1);
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private OrderResponse placeOrder(int quantity, String idempotencyKey) {
        return commandService.placeOrder(new CreateOrderRequest(
                List.of(new OrderItemRequest(PRODUCT_ID, quantity)),
                new PostalAddress("Ada Lovelace", null, "Keizersgracht 1", null, "Amsterdam",
                        null, "1015 CJ", "NL"),
                null,
                null,
                idempotencyKey), CUSTOMER);
    }

    private OrderStatus status(UUID orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    private List<SagaStep> stepsOf(UUID sagaId) {
        return stepLogRepository.findBySagaIdOrderByCreatedAtAsc(sagaId).stream()
                .map(SagaStepLog::getStep)
                .toList();
    }

    /** Asserts the saga took exactly these steps, and that every one of them was answered. */
    private void assertStepLog(UUID sagaId, SagaStep... expected) {
        List<SagaStepLog> rows = stepLogRepository.findBySagaIdOrderByCreatedAtAsc(sagaId);

        assertThat(rows).extracting(SagaStepLog::getStep).containsExactly(expected);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getStatus()).isNotEqualTo(StepStatus.SENT);
            assertThat(row.getCompletedAt())
                    .as("step %s was never answered", row.getStep())
                    .isNotNull();
        });
    }
}
