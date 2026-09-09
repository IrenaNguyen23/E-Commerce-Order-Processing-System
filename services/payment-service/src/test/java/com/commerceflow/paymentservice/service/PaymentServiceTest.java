package com.commerceflow.paymentservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
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
import com.commerceflow.common.event.ProcessPaymentCommand;
import com.commerceflow.common.event.PaymentCompletedEvent;
import com.commerceflow.common.event.PaymentFailedEvent;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.idempotency.IdempotencyService;
import com.commerceflow.common.outbox.OutboxService;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.paymentservice.config.PaymentProperties;
import com.commerceflow.paymentservice.dto.ProcessPaymentRequest;
import com.commerceflow.paymentservice.entity.Payment;
import com.commerceflow.paymentservice.entity.PaymentMethod;
import com.commerceflow.paymentservice.entity.PaymentStatus;
import com.commerceflow.common.event.PaymentCompletedEvent;
import com.commerceflow.paymentservice.gateway.PaymentGateway;
import com.commerceflow.paymentservice.mapper.PaymentMapper;
import com.commerceflow.paymentservice.repository.PaymentRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    private static final AuthenticatedUser CALLER = new AuthenticatedUser(
            UUID.randomUUID(), "ada@commerceflow.io", Set.of("CUSTOMER"), UUID.randomUUID().toString());

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private OutboxService outboxService;

    @Captor
    private ArgumentCaptor<DomainEvent> eventCaptor;

    @Captor
    private ArgumentCaptor<Payment> paymentCaptor;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(paymentRepository, paymentGateway, idempotencyService,
                outboxService, new PaymentMapper(), new PaymentProperties());

        when(idempotencyService.claim(anyString(), any(UUID.class), anyString())).thenReturn(true);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(call -> call.getArgument(0));
        when(paymentRepository.findByOrderId(any(UUID.class))).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("an approved charge is recorded and published as payment.completed")
    void approvedChargeIsPublished() {
        when(paymentGateway.charge(any(UUID.class), any(BigDecimal.class), anyString()))
                .thenReturn(PaymentGateway.ChargeResult.approved("SIM-ABC123"));

        ProcessPaymentCommand command = payCommand(new BigDecimal("1899.00"));
        service.processPayment(command);

        verify(paymentRepository).save(paymentCaptor.capture());
        Payment payment = paymentCaptor.getValue();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getTransactionId()).isEqualTo("SIM-ABC123");
        assertThat(payment.getProcessedAt()).isNotNull();

        PaymentCompletedEvent published = captured(PaymentCompletedEvent.class);
        assertThat(published.getOrderId()).isEqualTo(command.getOrderId());
        assertThat(published.getPaymentId()).isEqualTo(payment.getId());
        assertThat(published.getAmount()).isEqualByComparingTo(new BigDecimal("1899.00"));
        assertThat(published.getStatus()).isEqualTo("COMPLETED");
        // The linkage the orchestrator matches the reply on. Without it the saga would sit
        // waiting for an answer that already arrived.
        assertThat(published.getSagaId()).isEqualTo(command.getSagaId());
        assertThat(published.getCausationId()).isEqualTo(command.getEventId());
    }

    @Test
    @DisplayName("a decline is a business outcome: recorded, published, transaction commits")
    void declineIsPublishedNotThrown() {
        when(paymentGateway.charge(any(UUID.class), any(BigDecimal.class), anyString()))
                .thenReturn(PaymentGateway.ChargeResult.declined("INSUFFICIENT_FUNDS"));

        service.processPayment(payCommand(new BigDecimal("25000.00")));

        verify(paymentRepository).save(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(paymentCaptor.getValue().getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");

        PaymentFailedEvent published = captured(PaymentFailedEvent.class);
        assertThat(published.getReason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("an acquirer transport failure surfaces to the saga instead of hanging it")
    void gatewayErrorSurfacesAsFailure() {
        when(paymentGateway.charge(any(UUID.class), any(BigDecimal.class), anyString()))
                .thenThrow(new IllegalStateException("connection reset"));

        service.processPayment(payCommand(new BigDecimal("100.00")));

        PaymentFailedEvent published = captured(PaymentFailedEvent.class);
        assertThat(published.getReason()).isEqualTo(PaymentService.REASON_GATEWAY_ERROR);
    }

    @Test
    @DisplayName("a re-sent command answers from the existing payment without charging again")
    void resentCommandRepliesWithoutChargingAgain() {
        Payment existing = settledPayment();
        when(paymentRepository.findByOrderId(any(UUID.class))).thenReturn(Optional.of(existing));

        ProcessPaymentCommand retry = payCommand(new BigDecimal("1899.00"));
        retry.setAttempt(2);

        service.processPayment(retry);

        verify(paymentGateway, never()).charge(any(UUID.class), any(BigDecimal.class), anyString());

        // Answered, not ignored. Silence here would deadlock the saga: the orchestrator re-sent
        // precisely because no answer arrived, so staying quiet guarantees none ever will.
        PaymentCompletedEvent reply = captured(PaymentCompletedEvent.class);
        assertThat(reply.getPaymentId()).isEqualTo(existing.getId());
        assertThat(reply.getCausationId()).isEqualTo(retry.getEventId());
    }

    @Test
    @DisplayName("a PENDING payment is reconciled with the acquirer rather than guessed at")
    void pendingPaymentIsReconciled() {
        Payment pending = settledPayment();
        pending.setStatus(PaymentStatus.PENDING);
        pending.setGatewayReference("pi_123");
        when(paymentRepository.findByOrderId(any(UUID.class))).thenReturn(Optional.of(pending));
        when(paymentGateway.reconcile("pi_123"))
                .thenReturn(PaymentGateway.ChargeResult.approved("ch_123", "pi_123"));

        service.processPayment(payCommand(new BigDecimal("1899.00")));

        // Never charge again — ask what happened to the charge already in flight.
        verify(paymentGateway, never()).charge(any(UUID.class), any(BigDecimal.class), anyString());
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(captured(PaymentCompletedEvent.class)).isNotNull();
    }

    @Test
    @DisplayName("a payment the acquirer is still working on stays unanswered")
    void stillInFlightStaysSilent() {
        Payment pending = settledPayment();
        pending.setStatus(PaymentStatus.PENDING);
        pending.setGatewayReference("pi_123");
        when(paymentRepository.findByOrderId(any(UUID.class))).thenReturn(Optional.of(pending));
        when(paymentGateway.reconcile("pi_123"))
                .thenReturn(PaymentGateway.ChargeResult.pending("pi_123"));

        service.processPayment(payCommand(new BigDecimal("1899.00")));

        // Silence is the only true answer here. A decline would cancel an order that may yet be
        // paid for; the webhook settles it, and failing that the saga parks for a human.
        verify(outboxService, never()).append(anyString(), anyString(), any(DomainEvent.class));
    }

    @Test
    @DisplayName("a charge the acquirer has not decided is recorded, not answered")
    void pendingChargeIsRecordedWithoutReplying() {
        when(paymentGateway.charge(any(UUID.class), any(BigDecimal.class), anyString()))
                .thenReturn(PaymentGateway.ChargeResult.pending("pi_new"));

        ProcessPaymentCommand command = payCommand(new BigDecimal("1899.00"));
        service.processPayment(command);

        verify(paymentRepository).save(paymentCaptor.capture());
        Payment saved = paymentCaptor.getValue();

        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getGatewayReference()).isEqualTo("pi_new");
        // The linkage is stored now because the webhook that settles this has no command in
        // scope, and a reply without it is one the orchestrator drops.
        assertThat(saved.getSagaId()).isEqualTo(command.getSagaId());
        assertThat(saved.getCommandId()).isEqualTo(command.getEventId());

        verify(outboxService, never()).append(anyString(), anyString(), any(DomainEvent.class));
    }

    @Test
    @DisplayName("a webhook settles the payment and replies with the stored saga linkage")
    void webhookSettlesAndReplies() {
        Payment pending = settledPayment();
        pending.setStatus(PaymentStatus.PENDING);
        pending.setGatewayReference("pi_123");
        UUID sagaId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        pending.setSagaId(sagaId);
        pending.setCommandId(commandId);
        when(paymentRepository.findByGatewayReference("pi_123")).thenReturn(Optional.of(pending));

        assertThat(service.settleFromGateway("pi_123", true, null)).isTrue();

        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        PaymentCompletedEvent reply = captured(PaymentCompletedEvent.class);
        assertThat(reply.getSagaId()).isEqualTo(sagaId);
        assertThat(reply.getCausationId()).isEqualTo(commandId);
    }

    @Test
    @DisplayName("a redelivered webhook changes nothing")
    void webhookIsIdempotent() {
        Payment settled = settledPayment();
        when(paymentRepository.findByGatewayReference("pi_123")).thenReturn(Optional.of(settled));

        // Providers retry webhooks. The first delivery wins; the rest must be no-ops rather than
        // a second reply that reopens a closed step.
        assertThat(service.settleFromGateway("pi_123", true, null)).isFalse();
        verify(outboxService, never()).append(anyString(), anyString(), any(DomainEvent.class));
    }

    @Test
    @DisplayName("a webhook for an unknown reference is dropped, not failed")
    void webhookForUnknownReferenceIsDropped() {
        when(paymentRepository.findByGatewayReference(anyString())).thenReturn(Optional.empty());

        // Another environment sharing the acquirer account. Failing would make the provider
        // retry it for days over an event we correctly do not care about.
        assertThat(service.settleFromGateway("pi_someone_else", true, null)).isFalse();
    }

    @Test
    @DisplayName("a redelivered command is dropped by the ledger, whose reply is already out")
    void redeliveryIsDropped() {
        when(idempotencyService.claim(anyString(), any(UUID.class), anyString())).thenReturn(false);

        service.processPayment(payCommand(new BigDecimal("1899.00")));

        verify(paymentGateway, never()).charge(any(UUID.class), any(BigDecimal.class), anyString());
    }

    @Test
    @DisplayName("the manual endpoint refuses to re-charge an already settled order")
    void manualProcessRejectsSettledOrder() {
        UUID orderId = UUID.randomUUID();
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(settledPayment()));

        assertThatThrownBy(() -> service.processManually(new ProcessPaymentRequest(
                orderId, "CF-1", new BigDecimal("10.00"), "EUR", PaymentMethod.CARD), CALLER))
                .isInstanceOf(ConflictException.class);

        verify(paymentGateway, never()).charge(any(UUID.class), any(BigDecimal.class), anyString());
    }

    private <T extends DomainEvent> T captured(Class<T> type) {
        verify(outboxService).append(anyString(), anyString(), eventCaptor.capture());
        DomainEvent event = eventCaptor.getValue();
        assertThat(event).isInstanceOf(type);
        return type.cast(event);
    }

    private static ProcessPaymentCommand payCommand(BigDecimal amount) {
        UUID orderId = UUID.randomUUID();
        return ProcessPaymentCommand.builder()
                .eventId(UUID.randomUUID())
                .eventType("PROCESS_PAYMENT")
                .timestamp(Instant.now())
                .sagaId(orderId)
                .orderId(orderId)
                .orderNumber("CF-20260826-000001")
                .reservationId(UUID.randomUUID())
                .userId(CALLER.userId())
                .userEmail(CALLER.email())
                .amount(amount)
                .currency("EUR")
                .build();
    }

    private static Payment settledPayment() {
        return Payment.builder()
                .id(UUID.randomUUID())
                .orderId(UUID.randomUUID())
                .amount(new BigDecimal("1899.00"))
                .currency("EUR")
                .status(PaymentStatus.COMPLETED)
                .method(PaymentMethod.CARD)
                .transactionId("SIM-EXISTING")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .processedAt(Instant.now())
                .build();
    }
}
