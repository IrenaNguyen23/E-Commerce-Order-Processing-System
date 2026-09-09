package com.commerceflow.paymentservice.kafka;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.commerceflow.common.constant.KafkaTopics;
import com.commerceflow.common.event.RefundReturnCommand;
import com.commerceflow.paymentservice.service.ReturnRefundService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Refund requests raised by a return.
 *
 * <p>Its own topic and its own consumer group, separate from {@code payment.commands}. Two
 * reasons, and both matter:
 *
 * <ul>
 *   <li>A return refund is not saga traffic. Nothing orchestrates it, nothing compensates it, and
 *       the orchestrator has no business hearing about it.</li>
 *   <li>Keeping it off the saga topic means a backlog of returns after a busy January cannot
 *       delay a customer's payment being taken today. Different work, different queue.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(
        topics = KafkaTopics.RETURN_REFUND_COMMANDS,
        groupId = ReturnRefundListener.CONSUMER_GROUP,
        id = "payment-return-refunds")
public class ReturnRefundListener {

    static final String CONSUMER_GROUP = "payment-service-returns";

    private final ReturnRefundService returnRefundService;

    @KafkaHandler
    public void onRefundReturn(@Payload RefundReturnCommand command) {
        log.debug("Refund requested for return {} on order {}", command.getReturnId(),
                command.getOrderId());
        returnRefundService.refund(command);
    }

    /**
     * Anything else. Dead-lettered rather than retried, so the partition keeps moving and the
     * message can be replayed after whatever deploy would understand it.
     */
    @KafkaHandler(isDefault = true)
    public void onUnknown(Object payload) {
        throw new IllegalArgumentException(
                "Unsupported message on " + KafkaTopics.RETURN_REFUND_COMMANDS + ": "
                        + payload.getClass().getName());
    }
}
