package com.commerceflow.paymentservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.commerceflow.common.constant.KafkaTopics;
import com.commerceflow.common.event.PaymentCompletedEvent;
import com.commerceflow.common.event.PaymentFailedEvent;
import com.commerceflow.common.event.ProcessPaymentCommand;
import com.commerceflow.common.testsupport.AbstractSagaIntegrationTest;
import com.commerceflow.paymentservice.entity.Payment;
import com.commerceflow.paymentservice.entity.PaymentStatus;
import com.commerceflow.paymentservice.repository.PaymentRepository;

/**
 * The Payment participant, commanded over real Kafka and PostgreSQL.
 *
 * <p>Payment is now told to charge rather than inferring that it should from overhearing that
 * stock was reserved. The tests are written the same way: send {@code PROCESS_PAYMENT}, assert
 * on the reply.
 *
 * <p>The decline threshold of the simulated acquirer makes both branches reachable without
 * breaking anything: an ordinary amount is approved, an amount at or above the threshold is
 * declined, deterministically.
 */
class PaymentSagaIntegrationTest extends AbstractSagaIntegrationTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    @DisplayName("PROCESS_PAYMENT charges the customer and replies payment.completed")
    void chargesAndReplies() {
        UUID orderId = UUID.randomUUID();

        try (Consumer<String, Object> replies = consumerFor(KafkaTopics.PAYMENT_COMPLETED)) {
            ProcessPaymentCommand command = command(KafkaTopics.PAYMENT_COMMANDS,
                    payCommand(orderId, new BigDecimal("1899.00")), orderId);

            PaymentCompletedEvent reply =
                    awaitMessage(replies, PaymentCompletedEvent.class, SAGA_TIMEOUT);

            assertThat(reply.getOrderId()).isEqualTo(orderId);
            assertThat(reply.getStatus()).isEqualTo("COMPLETED");
            assertThat(reply.getTransactionId()).isNotBlank();
            assertThat(reply.getAmount()).isEqualByComparingTo(new BigDecimal("1899.00"));
            assertThat(reply.getSagaId()).isEqualTo(orderId);
            assertThat(reply.getCausationId()).isEqualTo(command.getEventId());
        }

        await().atMost(SAGA_TIMEOUT).untilAsserted(() ->
                assertThat(paymentRepository.findByOrderId(orderId))
                        .map(Payment::getStatus)
                        .contains(PaymentStatus.COMPLETED));
    }

    @Test
    @DisplayName("an amount at the decline threshold replies payment.failed, and that is not an error")
    void declineReplies() {
        UUID orderId = UUID.randomUUID();

        try (Consumer<String, Object> replies = consumerFor(KafkaTopics.PAYMENT_FAILED)) {
            command(KafkaTopics.PAYMENT_COMMANDS,
                    payCommand(orderId, new BigDecimal("25000.00")), orderId);

            PaymentFailedEvent reply =
                    awaitMessage(replies, PaymentFailedEvent.class, SAGA_TIMEOUT);

            assertThat(reply.getOrderId()).isEqualTo(orderId);
            assertThat(reply.getReason()).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(reply.getSagaId()).isEqualTo(orderId);
        }

        // A decline is a recorded business outcome, not a rolled-back transaction.
        await().atMost(SAGA_TIMEOUT).untilAsserted(() ->
                assertThat(paymentRepository.findByOrderId(orderId))
                        .map(Payment::getStatus)
                        .contains(PaymentStatus.FAILED));
    }

    @Test
    @DisplayName("a re-sent command answers from the existing payment without charging twice")
    void resentCommandRepliesWithoutChargingAgain() {
        UUID orderId = UUID.randomUUID();

        command(KafkaTopics.PAYMENT_COMMANDS, payCommand(orderId, new BigDecimal("499.00")),
                orderId);
        await().atMost(SAGA_TIMEOUT).untilAsserted(() ->
                assertThat(paymentRepository.findByOrderId(orderId)).isPresent());

        Payment first = paymentRepository.findByOrderId(orderId).orElseThrow();

        try (Consumer<String, Object> replies = consumerFor(KafkaTopics.PAYMENT_COMPLETED)) {
            // A new command id, exactly as the orchestrator's timeout retry would send it.
            ProcessPaymentCommand retry = payCommand(orderId, new BigDecimal("499.00"));
            retry.setAttempt(2);
            command(KafkaTopics.PAYMENT_COMMANDS, retry, orderId);

            PaymentCompletedEvent reply =
                    awaitMessage(replies, PaymentCompletedEvent.class, SAGA_TIMEOUT);

            // Answered again — from the payment that already exists.
            assertThat(reply.getPaymentId()).isEqualTo(first.getId());
            assertThat(reply.getTransactionId()).isEqualTo(first.getTransactionId());
            assertThat(reply.getCausationId()).isEqualTo(retry.getEventId());
        }

        await().during(java.time.Duration.ofSeconds(3)).atMost(SAGA_TIMEOUT).untilAsserted(() ->
                assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getId())
                        .isEqualTo(first.getId()));
    }

    @Test
    @DisplayName("a redelivered command with the same id never charges the customer twice")
    void redeliveryNeverChargesTwice() {
        UUID orderId = UUID.randomUUID();
        ProcessPaymentCommand command = payCommand(orderId, new BigDecimal("499.00"));

        command(KafkaTopics.PAYMENT_COMMANDS, command, orderId);
        await().atMost(SAGA_TIMEOUT).untilAsserted(() ->
                assertThat(paymentRepository.findByOrderId(orderId)).isPresent());

        UUID firstPaymentId = paymentRepository.findByOrderId(orderId).orElseThrow().getId();

        // The same command id, then a different one for the same order. Neither may produce a
        // second charge: the ledger stops the first, the one-payment-per-order rule the second.
        publish(KafkaTopics.PAYMENT_COMMANDS, command);
        command(KafkaTopics.PAYMENT_COMMANDS, payCommand(orderId, new BigDecimal("499.00")),
                orderId);

        await().during(java.time.Duration.ofSeconds(4)).atMost(SAGA_TIMEOUT).untilAsserted(() -> {
            assertThat(paymentRepository.count()).isPositive();
            assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getId())
                    .isEqualTo(firstPaymentId);
        });
    }

    private static ProcessPaymentCommand payCommand(UUID orderId, BigDecimal amount) {
        return ProcessPaymentCommand.builder()
                .eventId(UUID.randomUUID())
                .timestamp(Instant.now())
                .orderId(orderId)
                .orderNumber("CF-IT-" + orderId.toString().substring(0, 8))
                .reservationId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .userEmail("ada@commerceflow.io")
                .amount(amount)
                .currency("EUR")
                .build();
    }
}
