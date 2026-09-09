package com.commerceflow.orderservice.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.commerceflow.orderservice.config.OrderProperties;
import com.commerceflow.orderservice.entity.Order;
import com.commerceflow.orderservice.entity.OrderStatus;
import com.commerceflow.orderservice.repository.OrderRepository;
import com.commerceflow.orderservice.saga.SagaRecoveryService.Outcome;
import com.commerceflow.orderservice.saga.entity.SagaInstance;
import com.commerceflow.orderservice.saga.entity.SagaState;
import com.commerceflow.orderservice.saga.entity.SagaStep;
import com.commerceflow.orderservice.saga.repository.SagaInstanceRepository;
import com.commerceflow.orderservice.saga.repository.SagaStepLogRepository;

/**
 * What happens to an overdue step, and — more importantly — what does not.
 *
 * <p>These tests exist because the first version of this class parked every exhausted step, and
 * parking is wrong in two directions. Parking a reserve leaves stock held for an order that will
 * never be placed. Parking a release leaves stock held for an order that was already cancelled.
 * Both are silent: no error, no failed request, just a shop that can sell less than it thinks.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SagaRecoveryServiceTest {

    @Mock
    private SagaInstanceRepository sagaRepository;

    @Mock
    private SagaStepLogRepository stepLogRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderSagaOrchestrator orchestrator;

    private SagaRecoveryService recovery;
    private OrderProperties properties;
    private SagaInstance saga;
    private Order order;

    @BeforeEach
    void setUp() {
        properties = new OrderProperties();
        recovery = new SagaRecoveryService(sagaRepository, stepLogRepository, orderRepository,
                orchestrator, properties);

        UUID orderId = UUID.randomUUID();
        order = Order.builder()
                .id(orderId)
                .orderNumber("CF-20260827-000001")
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
                .stepDeadline(Instant.now().minusSeconds(60))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(sagaRepository.findById(orderId)).thenReturn(Optional.of(saga));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(stepLogRepository.findByCommandId(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("within budget, the command is simply sent again")
    void resendsWithinBudget() {
        assertThat(recovery.chase(saga.getId())).isEqualTo(Outcome.RESENT);

        verify(orchestrator).resend(saga, order);
        assertThat(saga.getState()).isEqualTo(SagaState.STARTED);
    }

    @Test
    @DisplayName("a reserve that used up its retries unwinds the saga instead of parking it")
    void exhaustedReserveAbandons() {
        saga.setAttempt(properties.getSaga().getMaxAttempts());

        assertThat(recovery.chase(saga.getId())).isEqualTo(Outcome.ABANDONED);

        verify(orchestrator).abandon(saga, order);
        // Parking here is the bug this test exists to prevent: it would hold the stock forever.
        assertThat(saga.getState()).isNotEqualTo(SagaState.STALLED);
    }

    @Test
    @DisplayName("a payment that used up its retries parks, and is never abandoned")
    void exhaustedPaymentParks() {
        saga.setCurrentStep(SagaStep.PROCESS_PAYMENT);
        saga.setAttempt(properties.getSaga().getMaxAttempts());

        assertThat(recovery.chase(saga.getId())).isEqualTo(Outcome.STALLED);

        assertThat(saga.getState()).isEqualTo(SagaState.STALLED);
        // The customer may already have been charged. Unwinding on that guess is the one thing
        // this must never do on its own.
        verify(orchestrator, never()).abandon(any(), any());
        verify(orchestrator, never()).resend(any(), any());
    }

    @Test
    @DisplayName("a confirm that used up its retries parks: the goods are sold, keep holding them")
    void exhaustedConfirmParks() {
        saga.setCurrentStep(SagaStep.CONFIRM_INVENTORY);
        saga.setAttempt(properties.getSaga().getMaxAttempts());

        assertThat(recovery.chase(saga.getId())).isEqualTo(Outcome.STALLED);

        assertThat(saga.getState()).isEqualTo(SagaState.STALLED);
        verify(orchestrator, never()).abandon(any(), any());
    }

    @Test
    @DisplayName("a release keeps being re-sent past its budget rather than giving up")
    void exhaustedReleaseKeepsTrying() {
        saga.beginCompensation(SagaStep.PROCESS_PAYMENT, "INSUFFICIENT_FUNDS");
        saga.setCurrentStep(SagaStep.RELEASE_INVENTORY);
        saga.setAttempt(properties.getSaga().getMaxAttempts() + 5);

        assertThat(recovery.chase(saga.getId())).isEqualTo(Outcome.RESENT_OVERDUE);

        verify(orchestrator).resend(saga, order);
        // Still compensating, not parked: the units have to go back eventually.
        assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING);
    }

    @Test
    @DisplayName("a reply that landed during the scan wins the race")
    void replyDuringScanWins() {
        saga.setStepDeadline(Instant.now().plusSeconds(120));

        assertThat(recovery.chase(saga.getId())).isEqualTo(Outcome.ALREADY_MOVED_ON);

        verify(orchestrator, never()).resend(any(), any());
        verify(orchestrator, never()).abandon(any(), any());
    }

    @Test
    @DisplayName("a terminal saga is left alone")
    void terminalSagaIsLeftAlone() {
        saga.finish();

        assertThat(recovery.chase(saga.getId())).isEqualTo(Outcome.ALREADY_MOVED_ON);

        verify(orchestrator, never()).resend(any(), any());
    }

    @Test
    @DisplayName("a saga whose order is gone parks: nothing can be rebuilt or unwound without it")
    void missingOrderParks() {
        when(orderRepository.findById(saga.getId())).thenReturn(Optional.empty());

        assertThat(recovery.chase(saga.getId())).isEqualTo(Outcome.STALLED);

        assertThat(saga.getState()).isEqualTo(SagaState.STALLED);
        verify(orchestrator, never()).abandon(any(), any());
    }

    @Test
    @DisplayName("a saga that no longer exists is not an error")
    void missingSagaIsNotAnError() {
        UUID unknown = UUID.randomUUID();
        when(sagaRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(recovery.chase(unknown)).isEqualTo(Outcome.GONE);
    }
}
