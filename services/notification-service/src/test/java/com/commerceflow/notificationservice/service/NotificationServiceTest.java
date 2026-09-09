package com.commerceflow.notificationservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import com.commerceflow.common.event.NotificationSendEvent;
import com.commerceflow.common.event.NotificationSentEvent;
import com.commerceflow.common.idempotency.IdempotencyService;
import com.commerceflow.common.outbox.OutboxService;
import com.commerceflow.notificationservice.channel.LoggingNotificationSender;
import com.commerceflow.notificationservice.channel.NotificationDeliveryException;
import com.commerceflow.notificationservice.channel.NotificationSender;
import com.commerceflow.notificationservice.config.NotificationProperties;
import com.commerceflow.notificationservice.entity.Notification;
import com.commerceflow.notificationservice.entity.NotificationChannel;
import com.commerceflow.notificationservice.entity.NotificationStatus;
import com.commerceflow.notificationservice.entity.NotificationType;
import com.commerceflow.notificationservice.mapper.NotificationMapper;
import com.commerceflow.notificationservice.repository.NotificationRepository;

/**
 * Notification renders and delivers what it is told to; it no longer decides what a completed
 * order deserves. So every test here sends a command and checks two things: the message that was
 * produced, and the reply that went back — because a step that delivers silently stalls the saga
 * just as surely as one that does nothing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private OutboxService outboxService;

    @Captor
    private ArgumentCaptor<Notification> notificationCaptor;

    @Captor
    private ArgumentCaptor<DomainEvent> replyCaptor;

    private NotificationService service;
    private NotificationSender sender;

    @BeforeEach
    void setUp() {
        sender = org.mockito.Mockito.spy(new LoggingNotificationSender());
        service = new NotificationService(notificationRepository, new NotificationTemplateRenderer(),
                List.of(sender), idempotencyService, outboxService, new NotificationMapper(),
                new NotificationProperties());

        when(idempotencyService.claim(anyString(), any(UUID.class), anyString())).thenReturn(true);
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("an ORDER_CONFIRMED command renders the order details and replies")
    void confirmsCompletedOrder() {
        NotificationSendEvent command = command("ORDER_CONFIRMED", Map.of(
                "orderNumber", "CF-20260826-000001",
                "totalAmount", "1899.00",
                "currency", "EUR",
                "paymentId", UUID.randomUUID().toString()));

        service.onNotificationRequested(command);

        verify(notificationRepository).save(notificationCaptor.capture());
        Notification notification = notificationCaptor.getValue();

        assertThat(notification.getType()).isEqualTo(NotificationType.ORDER_CONFIRMED);
        assertThat(notification.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(notification.getRecipient()).isEqualTo("ada@commerceflow.io");
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getSubject()).contains("CF-20260826-000001");
        assertThat(notification.getContent()).contains("1899.00").contains("EUR");
        // Nothing may be left unsubstituted in a customer facing message.
        assertThat(notification.getContent()).doesNotContain("{").doesNotContain("}");

        NotificationSentEvent reply = capturedReply();
        assertThat(reply.getStatus()).isEqualTo("SENT");
        assertThat(reply.getReferenceId()).isEqualTo(command.getReferenceId());
        // The linkage the orchestrator closes the saga on.
        assertThat(reply.getSagaId()).isEqualTo(command.getSagaId());
        assertThat(reply.getCausationId()).isEqualTo(command.getEventId());
    }

    @Test
    @DisplayName("an ORDER_CANCELLED command explains the failure")
    void explainsCancelledOrder() {
        service.onNotificationRequested(command("ORDER_CANCELLED", Map.of(
                "orderNumber", "CF-20260826-000002",
                "failedStep", "payment",
                "reason", "insufficient funds",
                "refundLine", "You have not been charged.")));

        verify(notificationRepository).save(notificationCaptor.capture());
        Notification notification = notificationCaptor.getValue();

        assertThat(notification.getType()).isEqualTo(NotificationType.ORDER_CANCELLED);
        assertThat(notification.getContent()).contains("insufficient funds");
        assertThat(notification.getContent()).contains("not been charged");
        assertThat(notification.getContent()).doesNotContain("{");
    }

    @Test
    @DisplayName("a cancelled order that was paid for is not told nobody charged them")
    void cancelledAfterPaymentIsToldAboutTheRefund() {
        // This template used to end with a fixed "You have not been charged." That is true of an
        // order cancelled before the payment step, and it is the worst possible thing to tell a
        // customer who is owed a refund — which, since orders can now be cancelled after they
        // complete, is a real case rather than a hypothetical one.
        service.onNotificationRequested(command("ORDER_CANCELLED", Map.of(
                "orderNumber", "CF-20260826-000003",
                "failedStep", "notification",
                "reason", "cancelled at your request",
                "refundLine", "We are refunding what you paid.")));

        verify(notificationRepository).save(notificationCaptor.capture());
        String content = notificationCaptor.getValue().getContent();

        assertThat(content).contains("refunding what you paid");
        assertThat(content).doesNotContain("not been charged");
    }

    @Test
    @DisplayName("a command from outside the saga is delivered, and its reply carries no sagaId")
    void handlesNonSagaCommand() {
        NotificationSendEvent welcome = NotificationSendEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("NOTIFICATION_SEND")
                .timestamp(Instant.now())
                .userId(UUID.randomUUID())
                .recipient("ada@commerceflow.io")
                .channel("EMAIL")
                .templateCode("USER_WELCOME")
                .subject("Welcome to CommerceFlow")
                .params(Map.of("fullName", "Ada Lovelace"))
                .build();

        service.onNotificationRequested(welcome);

        verify(notificationRepository).save(notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().getType()).isEqualTo(NotificationType.USER_WELCOME);
        assertThat(notificationCaptor.getValue().getContent()).contains("Ada Lovelace");

        // No saga to belong to, and the reply says so — which is how the orchestrator knows to
        // ignore it rather than hunting for a saga that was never started.
        assertThat(capturedReply().getSagaId()).isNull();
    }

    @Test
    @DisplayName("an undeliverable message is recorded as FAILED but still answers the command")
    void deliveryFailureStillAnswers() {
        doThrow(new NotificationDeliveryException("SMTP refused the recipient"))
                .when(sender).send(any(Notification.class));

        service.onNotificationRequested(command("ORDER_CONFIRMED",
                Map.of("orderNumber", "CF-20260826-000003")));

        verify(notificationRepository).save(notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notificationCaptor.getValue().getRetryCount()).isEqualTo(1);

        // A bounced email is a delivery problem. Leaving a paid order looking unfinished
        // because of one would be worse, so the saga still gets its answer.
        NotificationSentEvent reply = capturedReply();
        assertThat(reply.getStatus()).isEqualTo("FAILED");
        assertThat(reply.getFailureReason()).contains("SMTP refused");
    }

    @Test
    @DisplayName("a command with no recipient stores nothing but still answers")
    void missingRecipientStillAnswers() {
        NotificationSendEvent command = command("ORDER_CONFIRMED",
                Map.of("orderNumber", "CF-20260826-000004"));
        command.setRecipient(null);

        service.onNotificationRequested(command);

        // Nothing to store and nothing a retry would fix...
        verify(notificationRepository, never()).save(any(Notification.class));
        // ...but the orchestrator is waiting on this step, so it hears about the failure rather
        // than sitting until the deadline expires.
        NotificationSentEvent reply = capturedReply();
        assertThat(reply.getStatus()).isEqualTo("FAILED");
        assertThat(reply.getFailureReason()).isEqualTo("NO_RECIPIENT");
        assertThat(reply.getSagaId()).isEqualTo(command.getSagaId());
    }

    @Test
    @DisplayName("a redelivered command is dropped by the ledger, whose reply is already out")
    void redeliveryIsDropped() {
        when(idempotencyService.claim(anyString(), any(UUID.class), anyString())).thenReturn(false);

        service.onNotificationRequested(command("ORDER_CONFIRMED",
                Map.of("orderNumber", "CF-20260826-000005")));

        verify(notificationRepository, never()).save(any(Notification.class));
        verify(outboxService, never()).append(anyString(), anyString(), any(DomainEvent.class));
    }

    /** A saga command, as the orchestrator would send it. */
    private static NotificationSendEvent command(String template, Map<String, String> params) {
        UUID orderId = UUID.randomUUID();
        return NotificationSendEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("NOTIFICATION_SEND")
                .timestamp(Instant.now())
                .sagaId(orderId)
                .userId(UUID.randomUUID())
                .referenceId(orderId)
                .recipient("ada@commerceflow.io")
                .channel("EMAIL")
                .templateCode(template)
                .subject("Order " + params.getOrDefault("orderNumber", "?"))
                .params(params)
                .build();
    }

    private NotificationSentEvent capturedReply() {
        verify(outboxService).append(anyString(), anyString(), replyCaptor.capture());
        DomainEvent reply = replyCaptor.getValue();
        assertThat(reply).isInstanceOf(NotificationSentEvent.class);
        return (NotificationSentEvent) reply;
    }
}
