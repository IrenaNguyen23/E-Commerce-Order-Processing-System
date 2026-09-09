package com.commerceflow.inventoryservice.kafka;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.commerceflow.common.constant.KafkaTopics;
import com.commerceflow.common.event.ConfirmInventoryCommand;
import com.commerceflow.common.event.ReleaseInventoryCommand;
import com.commerceflow.common.event.ReserveInventoryCommand;
import com.commerceflow.common.event.RestockInventoryCommand;
import com.commerceflow.common.kafka.SagaConsumerGroups;
import com.commerceflow.inventoryservice.service.InventoryReservationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The only saga input Inventory Service has.
 *
 * <p>One topic, one consumer group, four commands — and nothing else. Inventory does not
 * subscribe to Payment's outcome, to Order's outcome, or to anything either of them says. If a
 * listener ever appears in this class pointed at another service's topic, orchestration has
 * quietly reverted to choreography.
 *
 * <p>All four commands share {@code inventory.commands} so they share a partition per order,
 * which is what guarantees a release can never overtake the reserve it compensates. The
 * {@code __TypeId__} header picks the handler; {@code @KafkaHandler} does the dispatch.
 *
 * <p>Adapters only — each method logs and delegates to the transactional service, so retry,
 * dead-lettering and idempotency stay in exactly one place each.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(
        topics = KafkaTopics.INVENTORY_COMMANDS,
        groupId = SagaConsumerGroups.INVENTORY_SERVICE,
        id = "inventory-commands")
public class InventoryCommandListener {

    private final InventoryReservationService reservationService;

    @KafkaHandler
    public void onReserve(@Payload ReserveInventoryCommand command,
                          @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                          @Header(KafkaHeaders.OFFSET) long offset) {
        log.debug("Command {} for order {} attempt {} ({}-{})", command.getEventType(),
                command.getOrderId(), command.getAttempt(), partition, offset);
        reservationService.reserve(command);
    }

    @KafkaHandler
    public void onRelease(@Payload ReleaseInventoryCommand command) {
        log.debug("Command {} for order {} attempt {}", command.getEventType(),
                command.getOrderId(), command.getAttempt());
        reservationService.release(command);
    }

    @KafkaHandler
    public void onConfirm(@Payload ConfirmInventoryCommand command) {
        log.debug("Command {} for order {} attempt {}", command.getEventType(),
                command.getOrderId(), command.getAttempt());
        reservationService.confirm(command);
    }

    @KafkaHandler
    public void onRestock(@Payload RestockInventoryCommand command) {
        log.debug("Command {} for order {} attempt {}", command.getEventType(),
                command.getOrderId(), command.getAttempt());
        reservationService.restock(command);
    }

    /**
     * Anything this service does not recognise.
     *
     * <p>Almost certainly a newer orchestrator sending a command an older Inventory Service has
     * not learned yet. Throwing sends it to the dead-letter topic where it can be inspected and
     * replayed after the deploy, which is better than retrying forever and blocking the partition
     * — and therefore every other order that hashes to it.
     */
    @KafkaHandler(isDefault = true)
    public void onUnknown(Object payload) {
        throw new IllegalArgumentException(
                "Unsupported command on " + KafkaTopics.INVENTORY_COMMANDS + ": "
                        + payload.getClass().getName());
    }
}
