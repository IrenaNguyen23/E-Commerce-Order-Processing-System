package com.commerceflow.inventoryservice.review;

import java.time.Instant;
import java.util.UUID;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.constant.KafkaTopics;
import com.commerceflow.common.event.OrderCompletedEvent;
import com.commerceflow.common.event.OrderLineItem;
import com.commerceflow.common.idempotency.IdempotencyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Records who has bought what, from the orders that complete.
 *
 * <h2>This is not a saga participant</h2>
 *
 * <p>Worth saying plainly, because everything else in this service that reads Kafka is. The
 * orchestrator sends commands to named participants and waits for their replies; nothing waits for
 * this. {@code order.completed} is an <em>announcement</em>, and this is a subscriber that Order
 * Service does not know exists.
 *
 * <p>That is exactly the property the domain events were kept for when the saga moved from
 * choreography to orchestration. Adding this consumer required no change to the flow, no new step,
 * no reply topic, and no risk to an order: if this listener is down for a day, some reviews are
 * missing a badge and nothing else happens.
 *
 * <h2>It answers one question and stores nothing else</h2>
 *
 * <p>"Has this customer bought this product." Not how many, not for how much, not when they last
 * did. Anything more would be a copy of Order Service's data drifting quietly out of step with it,
 * which is the usual way this pattern goes wrong.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(
        topics = KafkaTopics.ORDER_COMPLETED,
        groupId = VerifiedPurchaseListener.CONSUMER_GROUP,
        id = "verified-purchases")
public class VerifiedPurchaseListener {

    /** Its own group, so this consumer's progress is independent of the notification one. */
    static final String CONSUMER_GROUP = "inventory-service-reviews";

    private final VerifiedPurchaseRepository purchases;
    private final IdempotencyService idempotencyService;

    /**
     * @param event a completed order. Redelivery is harmless twice over: the ledger claims the
     *     event id, and the unique index on (user, product) would refuse a duplicate anyway.
     */
    @org.springframework.kafka.annotation.KafkaHandler
    @Transactional
    public void onOrderCompleted(OrderCompletedEvent event) {
        if (event.getUserId() == null || event.getItems() == null) {
            return;
        }

        // Its own consumer group, so this claims the event independently of any other consumer
        // of the same topic. Sharing a group id would mean one consumer's progress hid the other's.
        if (!idempotencyService.claim(CONSUMER_GROUP, event.getEventId(), event.getEventType())) {
            return;
        }

        int recorded = 0;
        for (OrderLineItem line : event.getItems()) {
            UUID productId = line.getProductId();
            if (productId == null
                    || purchases.existsByUserIdAndProductId(event.getUserId(), productId)) {
                continue;
            }
            purchases.save(VerifiedPurchase.builder()
                    .id(UUID.randomUUID())
                    .userId(event.getUserId())
                    .productId(productId)
                    .purchasedAt(Instant.now())
                    .build());
            recorded++;
        }

        if (recorded > 0) {
            log.debug("Recorded {} verified purchase(s) from order {}", recorded,
                    event.getOrderNumber());
        }
    }

    /**
     * Anything else on this topic.
     *
     * <p>Present so an unexpected payload is ignored rather than poisoning the partition. This
     * consumer is a nice-to-have; nothing it does is worth stopping a topic over.
     */
    @org.springframework.kafka.annotation.KafkaHandler(isDefault = true)
    public void onOther(Object payload) {
        log.debug("Ignoring {} on {}", payload.getClass().getSimpleName(),
                KafkaTopics.ORDER_COMPLETED);
    }
}
