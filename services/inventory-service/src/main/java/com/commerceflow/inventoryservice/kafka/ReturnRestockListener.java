package com.commerceflow.inventoryservice.kafka;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.commerceflow.common.constant.KafkaTopics;
import com.commerceflow.common.event.RestockReturnedGoodsCommand;
import com.commerceflow.inventoryservice.service.ReturnRestockService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Goods a customer sent back, going onto the shelf.
 *
 * <p>Its own topic and consumer group rather than {@code inventory.commands}: a January backlog of
 * returns must not queue in front of a reservation somebody is waiting on at checkout. Different
 * work, different queue — the same arrangement as return refunds.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(
        topics = KafkaTopics.RETURN_RESTOCK_COMMANDS,
        groupId = ReturnRestockListener.CONSUMER_GROUP,
        id = "inventory-return-restock")
public class ReturnRestockListener {

    static final String CONSUMER_GROUP = "inventory-service-returns";

    private final ReturnRestockService returnRestockService;

    @KafkaHandler
    public void onRestock(@Payload RestockReturnedGoodsCommand command) {
        log.debug("Restocking return {} on order {}", command.getReturnId(), command.getOrderId());
        returnRestockService.restock(command);
    }

    /** Anything else: dead-lettered, so the partition keeps moving. */
    @KafkaHandler(isDefault = true)
    public void onUnknown(Object payload) {
        throw new IllegalArgumentException(
                "Unsupported message on " + KafkaTopics.RETURN_RESTOCK_COMMANDS + ": "
                        + payload.getClass().getName());
    }
}
