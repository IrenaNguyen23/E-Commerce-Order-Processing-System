package com.commerceflow.paymentservice.kafka;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.commerceflow.common.constant.KafkaTopics;
import com.commerceflow.common.event.ProcessPaymentCommand;
import com.commerceflow.common.event.RefundPaymentCommand;
import com.commerceflow.common.kafka.SagaConsumerGroups;
import com.commerceflow.paymentservice.service.PaymentService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The only saga input Payment Service has.
 *
 * <p>Payment is driven by an explicit {@code PROCESS_PAYMENT} command. It does not infer that it
 * should charge from anything it overhears, which keeps a consequential decision — when money
 * moves — in the orchestrator where the whole flow is visible, rather than in a subscription here
 * where it would be invisible to anyone reading the saga.
 *
 * <p>Adapters only — the method logs and delegates to the transactional service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(
        topics = KafkaTopics.PAYMENT_COMMANDS,
        groupId = SagaConsumerGroups.PAYMENT_SERVICE,
        id = "payment-commands")
public class PaymentCommandListener {

    private final PaymentService paymentService;

    @KafkaHandler
    public void onProcessPayment(@Payload ProcessPaymentCommand command) {
        log.debug("Command {} for order {} attempt {}", command.getEventType(),
                command.getOrderId(), command.getAttempt());
        paymentService.processPayment(command);
    }

    @KafkaHandler
    public void onRefund(@Payload RefundPaymentCommand command) {
        log.debug("Command {} for order {} attempt {}", command.getEventType(),
                command.getOrderId(), command.getAttempt());
        paymentService.refundPayment(command);
    }

    /**
     * Anything this service does not recognise — most likely a newer orchestrator sending a
     * command this version has not learned yet. Dead-lettered rather than retried forever, so the
     * partition keeps moving and the message can be replayed after the deploy.
     */
    @KafkaHandler(isDefault = true)
    public void onUnknown(Object payload) {
        throw new IllegalArgumentException(
                "Unsupported command on " + KafkaTopics.PAYMENT_COMMANDS + ": "
                        + payload.getClass().getName());
    }
}
