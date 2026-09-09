package com.commerceflow.orderservice.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.commerceflow.common.constant.KafkaTopics;
import com.commerceflow.common.event.InventoryConfirmedEvent;
import com.commerceflow.common.event.InventoryFailedEvent;
import com.commerceflow.common.event.InventoryReleasedEvent;
import com.commerceflow.common.event.InventoryReservedEvent;
import com.commerceflow.common.event.NotificationSentEvent;
import com.commerceflow.common.event.PaymentCompletedEvent;
import com.commerceflow.common.event.InventoryRestockedEvent;
import com.commerceflow.common.event.PaymentFailedEvent;
import com.commerceflow.common.event.PaymentRefundedEvent;
import com.commerceflow.common.kafka.SagaConsumerGroups;
import com.commerceflow.orderservice.saga.OrderSagaOrchestrator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Every reply the orchestrator is waiting for, in one class.
 *
 * <p>Nine topics, one consumer group, one destination. That concentration is the point: this is
 * the complete list of things that can move an order saga forward, and reading this file tells you
 * all of them without opening another service.
 *
 * <p>Adapters only — each method delegates straight to the orchestrator, so retry,
 * dead-lettering, idempotency and the state machine each live in exactly one place.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaReplyListener {

    private final OrderSagaOrchestrator orchestrator;

    @KafkaListener(
            topics = KafkaTopics.INVENTORY_RESERVED,
            groupId = SagaConsumerGroups.ORDER_SERVICE,
            id = "saga-inventory-reserved")
    public void onInventoryReserved(@Payload InventoryReservedEvent reply) {
        log.debug("Reply {} for saga {}", reply.getEventType(), reply.getSagaId());
        orchestrator.onInventoryReserved(reply);
    }

    @KafkaListener(
            topics = KafkaTopics.INVENTORY_FAILED,
            groupId = SagaConsumerGroups.ORDER_SERVICE,
            id = "saga-inventory-failed")
    public void onInventoryFailed(@Payload InventoryFailedEvent reply) {
        log.debug("Reply {} for saga {}", reply.getEventType(), reply.getSagaId());
        orchestrator.onInventoryFailed(reply);
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_COMPLETED,
            groupId = SagaConsumerGroups.ORDER_SERVICE,
            id = "saga-payment-completed")
    public void onPaymentCompleted(@Payload PaymentCompletedEvent reply) {
        log.debug("Reply {} for saga {}", reply.getEventType(), reply.getSagaId());
        orchestrator.onPaymentCompleted(reply);
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_FAILED,
            groupId = SagaConsumerGroups.ORDER_SERVICE,
            id = "saga-payment-failed")
    public void onPaymentFailed(@Payload PaymentFailedEvent reply) {
        log.debug("Reply {} for saga {}", reply.getEventType(), reply.getSagaId());
        orchestrator.onPaymentFailed(reply);
    }

    @KafkaListener(
            topics = KafkaTopics.INVENTORY_CONFIRMED,
            groupId = SagaConsumerGroups.ORDER_SERVICE,
            id = "saga-inventory-confirmed")
    public void onInventoryConfirmed(@Payload InventoryConfirmedEvent reply) {
        log.debug("Reply {} for saga {}", reply.getEventType(), reply.getSagaId());
        orchestrator.onInventoryConfirmed(reply);
    }

    @KafkaListener(
            topics = KafkaTopics.INVENTORY_RELEASED,
            groupId = SagaConsumerGroups.ORDER_SERVICE,
            id = "saga-inventory-released")
    public void onInventoryReleased(@Payload InventoryReleasedEvent reply) {
        log.debug("Reply {} for saga {}", reply.getEventType(), reply.getSagaId());
        orchestrator.onInventoryReleased(reply);
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_REFUNDED,
            groupId = SagaConsumerGroups.ORDER_SERVICE,
            id = "saga-payment-refunded")
    public void onPaymentRefunded(@Payload PaymentRefundedEvent reply) {
        log.debug("Reply {} for saga {}", reply.getEventType(), reply.getSagaId());
        orchestrator.onPaymentRefunded(reply);
    }

    @KafkaListener(
            topics = KafkaTopics.INVENTORY_RESTOCKED,
            groupId = SagaConsumerGroups.ORDER_SERVICE,
            id = "saga-inventory-restocked")
    public void onInventoryRestocked(@Payload InventoryRestockedEvent reply) {
        log.debug("Reply {} for saga {}", reply.getEventType(), reply.getSagaId());
        orchestrator.onInventoryRestocked(reply);
    }

    /**
     * The last step of both paths.
     *
     * <p>This topic also carries notifications that have nothing to do with an order — the
     * welcome mail Auth Service sends, for one. Those arrive with no {@code sagaId} and the
     * orchestrator drops them, which is why this listener does not try to filter them itself.
     */
    @KafkaListener(
            topics = KafkaTopics.NOTIFICATION_SENT,
            groupId = SagaConsumerGroups.ORDER_SERVICE,
            id = "saga-notification-sent")
    public void onNotificationSent(@Payload NotificationSentEvent reply) {
        log.debug("Reply {} for saga {}", reply.getEventType(), reply.getSagaId());
        orchestrator.onNotificationSent(reply);
    }
}
