package com.commerceflow.orderservice.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

import com.commerceflow.common.event.ConfirmInventoryCommand;
import com.commerceflow.common.event.DomainEvent;
import com.commerceflow.common.event.InventoryConfirmedEvent;
import com.commerceflow.common.event.InventoryFailedEvent;
import com.commerceflow.common.event.InventoryReleasedEvent;
import com.commerceflow.common.event.InventoryReservedEvent;
import com.commerceflow.common.event.NotificationSendEvent;
import com.commerceflow.common.event.NotificationSentEvent;
import com.commerceflow.common.event.OrderCancelledEvent;
import com.commerceflow.common.event.OrderCompletedEvent;
import com.commerceflow.common.event.PaymentCompletedEvent;
import com.commerceflow.common.event.InventoryRestockedEvent;
import com.commerceflow.common.event.PaymentRefundedEvent;
import com.commerceflow.common.event.RefundPaymentCommand;
import com.commerceflow.common.event.RestockInventoryCommand;
import com.commerceflow.common.event.PaymentFailedEvent;
import com.commerceflow.common.event.ProcessPaymentCommand;
import com.commerceflow.common.event.ReleaseInventoryCommand;
import com.commerceflow.common.idempotency.IdempotencyService;
import com.commerceflow.common.outbox.OutboxEvent;
import com.commerceflow.common.outbox.OutboxService;
import com.commerceflow.orderservice.config.OrderProperties;
import com.commerceflow.orderservice.entity.Order;
import com.commerceflow.orderservice.entity.OrderStatus;
import com.commerceflow.orderservice.mapper.OrderMapper;
import com.commerceflow.orderservice.repository.OrderRepository;
import com.commerceflow.orderservice.promotion.CouponService;
import com.commerceflow.orderservice.saga.entity.SagaInstance;
import com.commerceflow.orderservice.saga.entity.SagaState;
import com.commerceflow.orderservice.saga.entity.SagaStep;
import com.commerceflow.orderservice.saga.entity.StockUndo;
import com.commerceflow.orderservice.saga.repository.SagaInstanceRepository;
import com.commerceflow.orderservice.saga.repository.SagaStepLogRepository;
import com.commerceflow.orderservice.service.OrderProjectionService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The state machine, in isolation.
 *
 * <p>Each test asserts on the one thing that defines an orchestrator: given the saga is at a
 * step and a reply arrives, which command goes out next and what does the order become. The
 * integration test proves the wiring; this proves the decisions.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderSagaOrchestratorTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private SagaInstanceRepository sagaRepository;

    @Mock
    private SagaStepLogRepository stepLogRepository;

    @Mock
    private OrderProjectionService projectionService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private OutboxService outboxService;

    @Mock
    private OrderMapper orderMapper;

    @Captor
    private ArgumentCaptor<DomainEvent> messageCaptor;

    @Mock
    private CouponService couponService;

    private OrderSagaOrchestrator orchestrator;
    private Order order;
    private SagaInstance saga;

    @BeforeEach
    void setUp() {
        orchestrator = new OrderSagaOrchestrator(orderRepository, sagaRepository,
                stepLogRepository, projectionService, idempotencyService, outboxService,
                couponService, orderMapper, new OrderProperties(), new SimpleMeterRegistry());

        UUID orderId = UUID.randomUUID();
        order = Order.builder()
                .id(orderId)
                .orderNumber("CF-20260826-000001")
                .userId(UUID.randomUUID())
                .userEmail("ada@commerceflow.io")
                .status(OrderStatus.CREATED)
                .totalAmount(new BigDecimal("1899.00"))
                .currency("EUR")
                .shippingAddress("Keizersgracht 1")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .items(new ArrayList<>())
                .build();

        saga = SagaInstance.builder()
                .id(orderId)
                .orderNumber(order.getOrderNumber())
                .userId(order.getUserId())
                .userEmail(order.getUserEmail())
                .state(SagaState.STARTED)
                .currentStep(SagaStep.RESERVE_INVENTORY)
                .currentCommandId(UUID.randomUUID())
                .attempt(1)
                .stepDeadline(Instant.now().plusSeconds(120))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(idempotencyService.claim(anyString(), any(), anyString())).thenReturn(true);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(sagaRepository.findById(orderId)).thenReturn(Optional.of(saga));
        when(orderMapper.toEventLines(any())).thenReturn(List.of());
        when(outboxService.append(anyString(), anyString(), any()))
                .thenAnswer(invocation -> outboxRowFor(invocation.getArgument(2)));
        when(stepLogRepository.findByCommandId(any())).thenReturn(Optional.empty());
    }

    // =====================================================================================
    // Forward path
    // =====================================================================================

    @Test
    @DisplayName("a reserve reply advances the order and commands Payment next")
    void reservedAdvancesToPayment() {
        UUID reservationId = UUID.randomUUID();

        orchestrator.onInventoryReserved(reply(InventoryReservedEvent.builder()
                .orderId(order.getId())
                .reservationId(reservationId)
                .build()));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.PROCESS_PAYMENT);
        // Held so the compensation never has to ask Inventory what it is holding.
        assertThat(saga.getReservationId()).isEqualTo(reservationId);

        ProcessPaymentCommand command = captureLast(ProcessPaymentCommand.class);
        assertThat(command.getSagaId()).isEqualTo(saga.getId());
        assertThat(command.getReservationId()).isEqualTo(reservationId);
        assertThat(command.getAmount()).isEqualByComparingTo(order.getTotalAmount());
    }

    @Test
    @DisplayName("a payment reply moves the order to PAID and commands the stock write-off")
    void paidAdvancesToConfirm() {
        atStep(SagaStep.PROCESS_PAYMENT, OrderStatus.INVENTORY_RESERVED);
        UUID paymentId = UUID.randomUUID();

        orchestrator.onPaymentCompleted(reply(PaymentCompletedEvent.builder()
                .orderId(order.getId())
                .paymentId(paymentId)
                .build()));

        // PAID, not COMPLETED: the stock has not been written off yet, and saying otherwise
        // would make the order look finished while a step is still outstanding.
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaymentId()).isEqualTo(paymentId);
        assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.CONFIRM_INVENTORY);
        assertThat(captureLast(ConfirmInventoryCommand.class).getOrderId())
                .isEqualTo(order.getId());
    }

    @Test
    @DisplayName("confirming the stock completes the order and asks for the confirmation email")
    void confirmedCompletesTheOrder() {
        atStep(SagaStep.CONFIRM_INVENTORY, OrderStatus.PAID);
        saga.setPaymentId(UUID.randomUUID());

        orchestrator.onInventoryConfirmed(reply(InventoryConfirmedEvent.builder()
                .orderId(order.getId())
                .build()));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.getCompletedAt()).isNotNull();
        verify(projectionService).project(order);

        assertThat(captured(OrderCompletedEvent.class)).isNotNull();
        NotificationSendEvent notify = captureLast(NotificationSendEvent.class);
        assertThat(notify.getTemplateCode()).isEqualTo("ORDER_CONFIRMED");
        assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.NOTIFY_CUSTOMER);
    }

    @Test
    @DisplayName("the notification reply closes the saga as COMPLETED")
    void notificationClosesTheSaga() {
        atStep(SagaStep.NOTIFY_CUSTOMER, OrderStatus.COMPLETED);

        orchestrator.onNotificationSent(reply(NotificationSentEvent.builder()
                .notificationId(UUID.randomUUID())
                .status("SENT")
                .build()));

        assertThat(saga.getState()).isEqualTo(SagaState.COMPLETED);
        assertThat(saga.getCurrentStep()).isNull();
        assertThat(saga.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("an undeliverable notification still closes the saga")
    void failedDeliveryStillClosesTheSaga() {
        atStep(SagaStep.NOTIFY_CUSTOMER, OrderStatus.COMPLETED);

        orchestrator.onNotificationSent(reply(NotificationSentEvent.builder()
                .notificationId(UUID.randomUUID())
                .status("FAILED")
                .failureReason("MAILBOX_FULL")
                .build()));

        // A bounced email must not leave a paid order looking unfinished forever.
        assertThat(saga.getState()).isEqualTo(SagaState.COMPLETED);
    }

    // =====================================================================================
    // Compensation
    // =====================================================================================

    @Test
    @DisplayName("a reserve failure cancels the order and skips straight to the notification")
    void reserveFailureCompensatesNothing() {
        orchestrator.onInventoryFailed(reply(InventoryFailedEvent.builder()
                .orderId(order.getId())
                .reason("OUT_OF_STOCK")
                .build()));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        // The published API value, not the orchestrator's internal step name.
        assertThat(order.getFailedStep()).isEqualTo("INVENTORY");
        assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING);

        // Nothing was reserved, so there is nothing to release.
        assertThat(captured(ReleaseInventoryCommand.class)).isNull();
        assertThat(captured(OrderCancelledEvent.class)).isNotNull();
        assertThat(captureLast(NotificationSendEvent.class).getTemplateCode())
                .isEqualTo("ORDER_CANCELLED");
        assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.NOTIFY_CUSTOMER);
    }

    @Test
    @DisplayName("a declined payment cancels the order and commands the release")
    void paymentFailureReleasesTheReservation() {
        atStep(SagaStep.PROCESS_PAYMENT, OrderStatus.INVENTORY_RESERVED);
        saga.setReservationId(UUID.randomUUID());

        orchestrator.onPaymentFailed(reply(PaymentFailedEvent.builder()
                .orderId(order.getId())
                .reason("INSUFFICIENT_FUNDS")
                .build()));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING);
        assertThat(saga.getFailedStep()).isEqualTo(SagaStep.PROCESS_PAYMENT);

        ReleaseInventoryCommand release = captureLast(ReleaseInventoryCommand.class);
        assertThat(release.getReason()).isEqualTo("PAYMENT_FAILED");
        assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.RELEASE_INVENTORY);
    }

    @Test
    @DisplayName("the release reply moves on to the cancellation email")
    void releaseAdvancesToNotify() {
        atStep(SagaStep.RELEASE_INVENTORY, OrderStatus.CANCELLED);
        saga.beginCompensation(SagaStep.PROCESS_PAYMENT, "INSUFFICIENT_FUNDS");
        saga.setCurrentStep(SagaStep.RELEASE_INVENTORY);

        orchestrator.onInventoryReleased(reply(InventoryReleasedEvent.builder()
                .orderId(order.getId())
                .reason("PAYMENT_FAILED")
                .build()));

        NotificationSendEvent notify = captureLast(NotificationSendEvent.class);
        assertThat(notify.getTemplateCode()).isEqualTo("ORDER_CANCELLED");
        assertThat(notify.getParams())
                .containsEntry("failedStep", "payment")
                .containsEntry("reason", "insufficient funds");
    }

    @Test
    @DisplayName("the compensating path closes the saga as COMPENSATED, not COMPLETED")
    void compensatedSagaEndsCompensated() {
        atStep(SagaStep.NOTIFY_CUSTOMER, OrderStatus.CANCELLED);
        saga.beginCompensation(SagaStep.PROCESS_PAYMENT, "CARD_DECLINED");
        saga.setCurrentStep(SagaStep.NOTIFY_CUSTOMER);

        orchestrator.onNotificationSent(reply(NotificationSentEvent.builder()
                .notificationId(UUID.randomUUID())
                .status("SENT")
                .build()));

        assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATED);
    }

    // =====================================================================================
    // Abandonment
    // =====================================================================================

    @Test
    @DisplayName("abandoning cancels the order and asks for the stock back")
    void abandonCancelsAndReleases() {
        orchestrator.abandon(saga, order);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getFailedStep()).isEqualTo("INVENTORY");
        assertThat(order.getFailureReason()).isEqualTo("INVENTORY_TIMED_OUT");
        assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING);

        // The release goes out even though nobody knows whether a reservation exists. It is
        // idempotent, and a participant with nothing to undo answers anyway.
        ReleaseInventoryCommand release = captureLast(ReleaseInventoryCommand.class);
        assertThat(release.getReason()).isEqualTo("INVENTORY_TIMED_OUT");
        assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.RELEASE_INVENTORY);

        assertThat(captured(OrderCancelledEvent.class)).isNotNull();
    }

    @Test
    @DisplayName("an abandoned saga tells the customer inventory timed out, not that it was out of stock")
    void abandonDoesNotInventARootCause() {
        orchestrator.abandon(saga, order);
        // Drive the compensation on to the notification step.
        atStep(SagaStep.RELEASE_INVENTORY, OrderStatus.CANCELLED);

        orchestrator.onInventoryReleased(reply(InventoryReleasedEvent.builder()
                .orderId(order.getId())
                .reason("INVENTORY_TIMED_OUT")
                .build()));

        // We do not know whether stock was available. Saying "out of stock" would be a guess
        // presented to a customer as a fact.
        assertThat(captureLast(NotificationSendEvent.class).getParams())
                .containsEntry("reason", "inventory timed out");
    }

    // =====================================================================================
    // Cancellation
    // =====================================================================================

    @Test
    @DisplayName("cancelling an unpaid order gives the hold back and charges nothing")
    void cancelUnpaidReleasesOnly() {
        atStep(SagaStep.NOTIFY_CUSTOMER, OrderStatus.INVENTORY_RESERVED);
        saga.finish();

        orchestrator.cancel(saga, order, "Changed my mind");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING);
        // No payment was ever taken, so there is nothing to send back.
        assertThat(captured(RefundPaymentCommand.class)).isNull();
        assertThat(captureLast(ReleaseInventoryCommand.class)).isNotNull();
        assertThat(saga.getStockUndo()).isEqualTo(StockUndo.RELEASE);
    }

    @Test
    @DisplayName("cancelling a paid order refunds first, then gives the hold back")
    void cancelPaidRefundsBeforeStock() {
        atStep(SagaStep.NOTIFY_CUSTOMER, OrderStatus.PAID);
        saga.setPaymentId(UUID.randomUUID());
        saga.finish();

        orchestrator.cancel(saga, order, "Changed my mind");

        // Money before goods: a customer chasing a refund is a worse outcome than a unit that
        // reappears a second later.
        RefundPaymentCommand refund = captureLast(RefundPaymentCommand.class);
        assertThat(refund.getAmount()).isEqualByComparingTo(order.getTotalAmount());
        assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.REFUND_PAYMENT);
        // The stock is not touched yet — it comes after the refund reply.
        assertThat(captured(ReleaseInventoryCommand.class)).isNull();
        assertThat(saga.getStockUndo()).isEqualTo(StockUndo.RELEASE);
    }

    @Test
    @DisplayName("cancelling a completed order restocks rather than releasing")
    void cancelCompletedRestocks() {
        atStep(SagaStep.NOTIFY_CUSTOMER, OrderStatus.COMPLETED);
        saga.setPaymentId(UUID.randomUUID());
        saga.finish();

        orchestrator.cancel(saga, order, "Returned");

        // The stock was written off as sold. Releasing a hold that no longer exists would leave
        // the units gone and reservedQuantity negative.
        assertThat(saga.getStockUndo()).isEqualTo(StockUndo.RESTOCK);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("a refunded payment moves on to returning the stock")
    void refundAdvancesToStock() {
        atStep(SagaStep.REFUND_PAYMENT, OrderStatus.CANCELLED);
        saga.beginCompensation(SagaStep.REFUND_PAYMENT, "Changed my mind");
        saga.setCurrentStep(SagaStep.REFUND_PAYMENT);
        saga.setStockUndo(StockUndo.RESTOCK);

        orchestrator.onPaymentRefunded(reply(PaymentRefundedEvent.builder()
                .orderId(order.getId())
                .success(true)
                .build()));

        assertThat(captureLast(RestockInventoryCommand.class)).isNotNull();
        assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.RESTOCK_INVENTORY);
    }

    @Test
    @DisplayName("a refund that failed does not strand the stock as well as the money")
    void failedRefundStillReturnsStock() {
        atStep(SagaStep.REFUND_PAYMENT, OrderStatus.CANCELLED);
        saga.beginCompensation(SagaStep.REFUND_PAYMENT, "Changed my mind");
        saga.setCurrentStep(SagaStep.REFUND_PAYMENT);
        saga.setStockUndo(StockUndo.RELEASE);

        orchestrator.onPaymentRefunded(reply(PaymentRefundedEvent.builder()
                .orderId(order.getId())
                .success(false)
                .failureReason("ACQUIRER_REFUSED")
                .build()));

        // Halting here would leave two problems where there was one: the customer owed money
        // *and* stock nobody can sell. The refund failure is escalated, the saga carries on.
        assertThat(captureLast(ReleaseInventoryCommand.class)).isNotNull();
        assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.RELEASE_INVENTORY);
    }

    @Test
    @DisplayName("restocking finishes the cancellation by telling the customer")
    void restockAdvancesToNotify() {
        atStep(SagaStep.RESTOCK_INVENTORY, OrderStatus.CANCELLED);
        saga.beginCompensation(SagaStep.REFUND_PAYMENT, "Returned");
        saga.setCurrentStep(SagaStep.RESTOCK_INVENTORY);

        orchestrator.onInventoryRestocked(reply(InventoryRestockedEvent.builder()
                .orderId(order.getId())
                .unitsRestocked(3)
                .build()));

        assertThat(captureLast(NotificationSendEvent.class).getTemplateCode())
                .isEqualTo("ORDER_CANCELLED");
        assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.NOTIFY_CUSTOMER);
    }

    // =====================================================================================
    // The guard
    // =====================================================================================

    @Test
    @DisplayName("a reply for a step the saga has passed is dropped, not applied")
    void supersededReplyChangesNothing() {
        atStep(SagaStep.PROCESS_PAYMENT, OrderStatus.INVENTORY_RESERVED);

        // A duplicate inventory.failed arriving after the reserve step closed.
        orchestrator.onInventoryFailed(reply(InventoryFailedEvent.builder()
                .orderId(order.getId())
                .reason("OUT_OF_STOCK")
                .build()));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.PROCESS_PAYMENT);
        verify(outboxService, never()).append(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a reply for a saga that is already terminal is dropped")
    void terminalSagaIgnoresLateReplies() {
        atStep(SagaStep.NOTIFY_CUSTOMER, OrderStatus.COMPLETED);
        saga.finish();

        orchestrator.onPaymentFailed(reply(PaymentFailedEvent.builder()
                .orderId(order.getId())
                .reason("INSUFFICIENT_FUNDS")
                .build()));

        // The order was paid for. Cancelling it now would be the worst possible outcome.
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        verify(outboxService, never()).append(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a reply with no sagaId never reaches the ledger, let alone the state machine")
    void replyWithoutSagaIdIsIgnored() {
        NotificationSentEvent orphan = NotificationSentEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("NOTIFICATION_SENT")
                .notificationId(UUID.randomUUID())
                .status("SENT")
                .build();

        orchestrator.onNotificationSent(orphan);

        verify(idempotencyService, never()).claim(anyString(), any(), anyString());
        verify(outboxService, never()).append(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a duplicate delivery is dropped by the ledger before anything moves")
    void duplicateDeliveryIsDropped() {
        when(idempotencyService.claim(anyString(), any(), anyString())).thenReturn(false);

        orchestrator.onInventoryReserved(reply(InventoryReservedEvent.builder()
                .orderId(order.getId())
                .reservationId(UUID.randomUUID())
                .build()));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        verify(outboxService, never()).append(anyString(), anyString(), any());
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    /** Stamps the envelope a real reply would arrive with. */
    private <T extends DomainEvent> T reply(T event) {
        event.setEventId(UUID.randomUUID());
        event.setEventType(event.getClass().getSimpleName());
        event.setSagaId(saga.getId());
        event.setCausationId(saga.getCurrentCommandId());
        return event;
    }

    /** Positions the saga and the order mid-flow, the way an earlier step would have left them. */
    private void atStep(SagaStep step, OrderStatus status) {
        saga.setCurrentStep(step);
        saga.setCurrentCommandId(UUID.randomUUID());
        order.setStatus(status);
    }

    private <T extends DomainEvent> T captureLast(Class<T> type) {
        T found = captured(type);
        assertThat(found).as("no %s was sent", type.getSimpleName()).isNotNull();
        return found;
    }

    private <T extends DomainEvent> T captured(Class<T> type) {
        verify(outboxService, org.mockito.Mockito.atLeast(0))
                .append(anyString(), anyString(), messageCaptor.capture());
        return messageCaptor.getAllValues().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private static OutboxEvent outboxRowFor(DomainEvent event) {
        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID());
        }
        return OutboxEvent.builder().id(UUID.randomUUID()).eventId(event.getEventId()).build();
    }
}
