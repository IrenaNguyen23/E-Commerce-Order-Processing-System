package com.commerceflow.notificationservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.commerceflow.common.constant.KafkaTopics;
import com.commerceflow.common.event.NotificationSendEvent;
import com.commerceflow.common.event.NotificationSentEvent;
import com.commerceflow.common.testsupport.AbstractSagaIntegrationTest;
import com.commerceflow.notificationservice.entity.Notification;
import com.commerceflow.notificationservice.entity.NotificationStatus;
import com.commerceflow.notificationservice.entity.NotificationType;
import com.commerceflow.notificationservice.repository.NotificationRepository;

/**
 * The Notification participant — the last step of both saga paths.
 *
 * <p>It no longer decides that a completed order deserves an email; it is told to send one, with
 * the template and the values already chosen. What it still owes the saga is an answer, and
 * these tests check for one in every case, including the ones where delivery is impossible.
 *
 * <p>The same topic carries messages from outside the saga. Those arrive with no {@code sagaId}
 * and must be delivered exactly the same way — Auth Service's welcome mail is not less important
 * for having no orchestrator behind it.
 */
class NotificationSagaIntegrationTest extends AbstractSagaIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    @DisplayName("an ORDER_CONFIRMED command is delivered and answered on notification.sent")
    void confirmsCompletedOrder() {
        UUID orderId = UUID.randomUUID();

        try (Consumer<String, Object> replies = consumerFor(KafkaTopics.NOTIFICATION_SENT)) {
            NotificationSendEvent command = command(KafkaTopics.NOTIFICATION_SEND,
                    notify(orderId, "ORDER_CONFIRMED", Map.of(
                            "orderNumber", "CF-IT-000001",
                            "totalAmount", "1899.00",
                            "currency", "EUR",
                            "paymentId", UUID.randomUUID().toString())),
                    orderId);

            NotificationSentEvent reply =
                    awaitMessage(replies, NotificationSentEvent.class, SAGA_TIMEOUT);

            assertThat(reply.getReferenceId()).isEqualTo(orderId);
            assertThat(reply.getStatus()).isEqualTo("SENT");
            // Without this the orchestrator cannot close the saga, and it would sit waiting.
            assertThat(reply.getSagaId()).isEqualTo(orderId);
            assertThat(reply.getCausationId()).isEqualTo(command.getEventId());
        }

        await().atMost(SAGA_TIMEOUT).untilAsserted(() -> {
            List<Notification> stored =
                    notificationRepository.findByReferenceIdOrderByCreatedAtAsc(orderId);
            assertThat(stored).hasSize(1);
            assertThat(stored.get(0).getType()).isEqualTo(NotificationType.ORDER_CONFIRMED);
            assertThat(stored.get(0).getStatus()).isEqualTo(NotificationStatus.SENT);
            assertThat(stored.get(0).getSubject()).contains("CF-IT-000001");
            // A customer must never see an unsubstituted placeholder.
            assertThat(stored.get(0).getContent()).doesNotContain("{").doesNotContain("}");
        });
    }

    @Test
    @DisplayName("an ORDER_CANCELLED command explains the failure to the customer")
    void explainsCancelledOrder() {
        UUID orderId = UUID.randomUUID();

        command(KafkaTopics.NOTIFICATION_SEND,
                notify(orderId, "ORDER_CANCELLED", Map.of(
                        "orderNumber", "CF-IT-000002",
                        "failedStep", "payment",
                        "reason", "insufficient funds")),
                orderId);

        await().atMost(SAGA_TIMEOUT).untilAsserted(() -> {
            List<Notification> stored =
                    notificationRepository.findByReferenceIdOrderByCreatedAtAsc(orderId);
            assertThat(stored).hasSize(1);
            assertThat(stored.get(0).getType()).isEqualTo(NotificationType.ORDER_CANCELLED);
            assertThat(stored.get(0).getContent()).contains("insufficient funds");
            assertThat(stored.get(0).getContent()).contains("not been charged");
        });
    }

    @Test
    @DisplayName("a command from outside the saga carries no sagaId, and is delivered anyway")
    void handlesNonSagaCommand() {
        UUID userId = UUID.randomUUID();

        try (Consumer<String, Object> replies = consumerFor(KafkaTopics.NOTIFICATION_SENT)) {
            // This is Auth Service's welcome mail, verbatim.
            publish(KafkaTopics.NOTIFICATION_SEND, NotificationSendEvent.builder()
                    .eventId(UUID.randomUUID())
                    .timestamp(java.time.Instant.now())
                    .userId(userId)
                    .referenceId(userId)
                    .recipient("ada@commerceflow.io")
                    .channel("EMAIL")
                    .templateCode("USER_WELCOME")
                    .subject("Welcome to CommerceFlow")
                    .params(Map.of("fullName", "Ada Lovelace"))
                    .build());

            NotificationSentEvent reply =
                    awaitMessage(replies, NotificationSentEvent.class, SAGA_TIMEOUT);

            // No saga to belong to, and the reply says so — which is how the orchestrator knows
            // to ignore it rather than hunting for a saga that was never started.
            assertThat(reply.getSagaId()).isNull();
        }

        await().atMost(SAGA_TIMEOUT).untilAsserted(() -> {
            List<Notification> stored =
                    notificationRepository.findByReferenceIdOrderByCreatedAtAsc(userId);
            assertThat(stored).hasSize(1);
            assertThat(stored.get(0).getType()).isEqualTo(NotificationType.USER_WELCOME);
            assertThat(stored.get(0).getContent()).contains("Ada Lovelace");
        });
    }

    @Test
    @DisplayName("a command with no recipient still answers, so the saga cannot hang on it")
    void undeliverableCommandStillReplies() {
        UUID orderId = UUID.randomUUID();

        try (Consumer<String, Object> replies = consumerFor(KafkaTopics.NOTIFICATION_SENT)) {
            NotificationSendEvent command = notify(orderId, "ORDER_CONFIRMED",
                    Map.of("orderNumber", "CF-IT-000004"));
            command.setRecipient(null);
            command(KafkaTopics.NOTIFICATION_SEND, command, orderId);

            NotificationSentEvent reply =
                    awaitMessage(replies, NotificationSentEvent.class, SAGA_TIMEOUT);

            assertThat(reply.getStatus()).isEqualTo("FAILED");
            assertThat(reply.getFailureReason()).isEqualTo("NO_RECIPIENT");
            assertThat(reply.getSagaId()).isEqualTo(orderId);
        }
    }

    @Test
    @DisplayName("a redelivered command does not notify the customer twice")
    void redeliveryDoesNotNotifyTwice() {
        UUID orderId = UUID.randomUUID();
        NotificationSendEvent command = notify(orderId, "ORDER_CONFIRMED",
                Map.of("orderNumber", "CF-IT-000003", "totalAmount", "99.00", "currency", "EUR"));
        command.setSagaId(orderId);

        publish(KafkaTopics.NOTIFICATION_SEND, command);
        await().atMost(SAGA_TIMEOUT).untilAsserted(() ->
                assertThat(notificationRepository.findByReferenceIdOrderByCreatedAtAsc(orderId))
                        .hasSize(1));

        publish(KafkaTopics.NOTIFICATION_SEND, command);

        await().during(java.time.Duration.ofSeconds(4)).atMost(SAGA_TIMEOUT).untilAsserted(() ->
                assertThat(notificationRepository.findByReferenceIdOrderByCreatedAtAsc(orderId))
                        .hasSize(1));
    }

    private static NotificationSendEvent notify(UUID orderId, String template,
                                                Map<String, String> params) {
        return NotificationSendEvent.builder()
                .eventId(UUID.randomUUID())
                .timestamp(java.time.Instant.now())
                .userId(UUID.randomUUID())
                .referenceId(orderId)
                .recipient("ada@commerceflow.io")
                .channel("EMAIL")
                .templateCode(template)
                .subject("Order " + params.getOrDefault("orderNumber", "?"))
                .params(params)
                .build();
    }
}
