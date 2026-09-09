package com.commerceflow.orderservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.orderservice.entity.Order;
import com.commerceflow.orderservice.entity.OrderStatus;
import com.commerceflow.orderservice.mapper.OrderMapper;
import com.commerceflow.orderservice.repository.OrderRepository;
import com.commerceflow.orderservice.promotion.CouponService;
import com.commerceflow.orderservice.saga.OrderSagaOrchestrator;
import com.commerceflow.orderservice.saga.entity.SagaInstance;
import com.commerceflow.orderservice.saga.entity.SagaState;
import com.commerceflow.orderservice.saga.entity.SagaStep;
import com.commerceflow.orderservice.saga.repository.SagaInstanceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Who may cancel, and — mostly — when they may not.
 *
 * <p>The dangerous case is cancelling into a running saga. There is a command outstanding, and
 * reopening the saga puts a second one against the same step; the pending reply is then dropped by
 * the step guard, which is harmless right up until the reply it dropped was "payment succeeded"
 * and a refund has already gone out for a charge nothing recorded.
 *
 * <p>It is not a race a test can reliably provoke, and it produces no error when it happens — only
 * an order whose money and stock disagree. So the rule is enforced up front and pinned here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderCancellationServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private SagaInstanceRepository sagaRepository;

    @Mock
    private OrderSagaOrchestrator orchestrator;

    @Mock
    private CouponService couponService;

    @Mock
    private AuditService auditService;

    private OrderCancellationService service;
    private Order order;
    private SagaInstance saga;
    private AuthenticatedUser customer;
    private AuthenticatedUser admin;

    @BeforeEach
    void setUp() {
        service = new OrderCancellationService(orderRepository, sagaRepository, orchestrator,
                new OrderMapper(new ObjectMapper()), couponService, auditService);

        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        customer = new AuthenticatedUser(customerId, "ada@commerceflow.io", Set.of("CUSTOMER"),
                UUID.randomUUID().toString());
        admin = new AuthenticatedUser(UUID.randomUUID(), "admin@commerceflow.io",
                Set.of("ADMIN"), UUID.randomUUID().toString());

        order = Order.builder()
                .id(orderId)
                .orderNumber("CF-20260827-000001")
                .userId(customerId)
                .userEmail(customer.email())
                .status(OrderStatus.COMPLETED)
                .subtotalAmount(new BigDecimal("1899.00"))
                .discountTotal(BigDecimal.ZERO)
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
                .userId(customerId)
                .userEmail(customer.email())
                .state(SagaState.COMPLETED)
                .attempt(1)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(sagaRepository.findById(orderId)).thenReturn(Optional.of(saga));
    }

    @Test
    @DisplayName("a customer can cancel their own finished order")
    void customerCancelsOwnOrder() {
        service.cancel(order.getId(), "Changed my mind", customer);

        verify(orchestrator).cancel(saga, order, "Changed my mind");
    }

    @Test
    @DisplayName("a blank reason still records who asked")
    void blankReasonIsFilledIn() {
        service.cancel(order.getId(), "   ", customer);

        verify(orchestrator).cancel(any(SagaInstance.class), any(Order.class),
                org.mockito.ArgumentMatchers.contains("the customer"));
    }

    @Test
    @DisplayName("a customer cannot cancel somebody else's order, or learn that it exists")
    void otherPeoplesOrdersAreInvisible() {
        AuthenticatedUser stranger = new AuthenticatedUser(UUID.randomUUID(), "eve@example.com",
                Set.of("CUSTOMER"), UUID.randomUUID().toString());

        // Not-found rather than forbidden: "this order exists but is not yours" is still an
        // answer about somebody else's order.
        assertThatThrownBy(() -> service.cancel(order.getId(), null, stranger))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(orchestrator, never()).cancel(any(), any(), anyString());
    }

    @Test
    @DisplayName("an administrator can cancel anybody's order")
    void adminCancelsAnyOrder() {
        service.cancel(order.getId(), "Fraud review", admin);

        verify(orchestrator).cancel(saga, order, "Fraud review");
    }

    @Test
    @DisplayName("an order whose saga is still running is refused, not raced")
    void runningSagaIsRefused() {
        saga.setState(SagaState.STARTED);
        saga.setCurrentStep(SagaStep.PROCESS_PAYMENT);

        assertThatThrownBy(() -> service.cancel(order.getId(), null, customer))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("still being processed");

        // The important half: nothing was unwound. Cancelling into a live payment step is how a
        // refund gets issued for a charge the system has not finished recording.
        verify(orchestrator, never()).cancel(any(), any(), anyString());
    }

    @Test
    @DisplayName("a compensating saga is refused for the same reason")
    void compensatingSagaIsRefused() {
        saga.setState(SagaState.COMPENSATING);

        assertThatThrownBy(() -> service.cancel(order.getId(), null, customer))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a parked saga is refused for a customer and allowed for an administrator")
    void parkedSagaIsAdminOnly() {
        saga.setState(SagaState.STALLED);
        saga.setCurrentStep(SagaStep.PROCESS_PAYMENT);

        assertThatThrownBy(() -> service.cancel(order.getId(), null, customer))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("contact support");

        // An administrator may, because a parked saga has already stopped — there is no in-flight
        // command to collide with. They are warned to check the payment first.
        service.cancel(order.getId(), "Reconciled with the acquirer", admin);
        verify(orchestrator).cancel(saga, order, "Reconciled with the acquirer");
    }

    @Test
    @DisplayName("an already cancelled order is not cancelled twice")
    void alreadyCancelledIsRefused() {
        order.setStatus(OrderStatus.CANCELLED);

        assertThatThrownBy(() -> service.cancel(order.getId(), null, customer))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already cancelled");
        verify(orchestrator, never()).cancel(any(), any(), anyString());
    }

    @Test
    @DisplayName("an order with no saga needs an operator, not a guess")
    void missingSagaIsRefused() {
        when(sagaRepository.findById(order.getId())).thenReturn(Optional.empty());

        // Cancelling without a saga would mean deciding what to undo from the order alone, which
        // is exactly the guesswork the saga row exists to remove.
        assertThatThrownBy(() -> service.cancel(order.getId(), null, admin))
                .hasMessageContaining("needs an operator");
    }

    @Test
    @DisplayName("the order is left for the orchestrator to change, not changed here")
    void serviceDoesNotTouchTheOrder() {
        service.cancel(order.getId(), "Changed my mind", customer);

        // This class decides *whether*; the orchestrator decides *what happens*. If the status
        // moved here too, two places would own the same transition.
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }
}
