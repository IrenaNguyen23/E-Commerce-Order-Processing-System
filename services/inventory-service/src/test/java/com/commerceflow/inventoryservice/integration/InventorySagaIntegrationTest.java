package com.commerceflow.inventoryservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.commerceflow.common.constant.KafkaTopics;
import com.commerceflow.common.event.ConfirmInventoryCommand;
import com.commerceflow.common.event.InventoryConfirmedEvent;
import com.commerceflow.common.event.InventoryFailedEvent;
import com.commerceflow.common.event.InventoryReleasedEvent;
import com.commerceflow.common.event.InventoryReservedEvent;
import com.commerceflow.common.event.OrderLineItem;
import com.commerceflow.common.event.ReleaseInventoryCommand;
import com.commerceflow.common.event.ReserveInventoryCommand;
import com.commerceflow.common.testsupport.AbstractSagaIntegrationTest;
import com.commerceflow.inventoryservice.entity.InventoryItem;
import com.commerceflow.inventoryservice.entity.InventoryReservation;
import com.commerceflow.inventoryservice.entity.ReservationStatus;
import com.commerceflow.inventoryservice.repository.InventoryItemRepository;
import com.commerceflow.inventoryservice.repository.InventoryReservationRepository;

/**
 * The Inventory participant, commanded over real Kafka and PostgreSQL.
 *
 * <p>The test plays the orchestrator: it sends a command and asserts on the reply. Every test
 * here checks two things, because the participant contract has two halves — the stock moved
 * correctly, <em>and</em> the orchestrator was told. A step that does the work silently is as
 * broken as one that does nothing, because the saga stalls either way.
 *
 * <p>It also proves the parts a unit test cannot: that Flyway's schema matches the JPA mapping,
 * that the outbox relay really publishes, and that the {@code __TypeId__} routing on a shared
 * command topic picks the right handler.
 */
class InventorySagaIntegrationTest extends AbstractSagaIntegrationTest {

    /** Seeded by {@code V2__seed_catalogue.sql} with 50 units. */
    private static final UUID LAPTOP = UUID.fromString("11111111-1111-1111-1111-111111111101");

    /** Seeded with exactly 2 units, so the out-of-stock branch is reachable without setup. */
    private static final UUID LIMITED = UUID.fromString("11111111-1111-1111-1111-111111111107");

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @Test
    @DisplayName("RESERVE_INVENTORY holds the stock and replies inventory.reserved")
    void reserveHoldsStockAndReplies() {
        UUID orderId = UUID.randomUUID();
        int before = availableQuantity(LAPTOP);

        try (Consumer<String, Object> replies = consumerFor(KafkaTopics.INVENTORY_RESERVED)) {
            command(KafkaTopics.INVENTORY_COMMANDS,
                    reserveCommand(orderId, LAPTOP, "CF-LAPTOP-001", 2), orderId);

            InventoryReservedEvent reply =
                    awaitMessage(replies, InventoryReservedEvent.class, SAGA_TIMEOUT);

            assertThat(reply.getOrderId()).isEqualTo(orderId);
            assertThat(reply.getStatus()).isEqualTo("RESERVED");
            assertThat(reply.getReservationId()).isNotNull();
            // Without the linkage the orchestrator cannot tell which saga, or which step, this
            // answers — and would drop it.
            assertThat(reply.getSagaId()).isEqualTo(orderId);
            assertThat(reply.getCausationId()).isNotNull();
        }

        await().atMost(SAGA_TIMEOUT).untilAsserted(() -> {
            assertThat(availableQuantity(LAPTOP)).isEqualTo(before - 2);
            assertThat(reservedQuantity(LAPTOP)).isGreaterThanOrEqualTo(2);
            assertThat(reservationRepository.findByOrderId(orderId))
                    .map(InventoryReservation::getStatus)
                    .contains(ReservationStatus.RESERVED);
        });
    }

    @Test
    @DisplayName("demand beyond stock replies inventory.failed and moves nothing")
    void reserveRepliesFailureWhenOutOfStock() {
        UUID orderId = UUID.randomUUID();
        int before = availableQuantity(LIMITED);

        try (Consumer<String, Object> replies = consumerFor(KafkaTopics.INVENTORY_FAILED)) {
            command(KafkaTopics.INVENTORY_COMMANDS,
                    reserveCommand(orderId, LIMITED, "CF-LIMITED-001", before + 10), orderId);

            InventoryFailedEvent reply =
                    awaitMessage(replies, InventoryFailedEvent.class, SAGA_TIMEOUT);

            assertThat(reply.getOrderId()).isEqualTo(orderId);
            assertThat(reply.getReason()).isEqualTo("OUT_OF_STOCK");
            assertThat(reply.getUnavailableSkus()).contains("CF-LIMITED-001");
            assertThat(reply.getSagaId()).isEqualTo(orderId);
        }

        assertThat(availableQuantity(LIMITED)).isEqualTo(before);
    }

    @Test
    @DisplayName("RELEASE_INVENTORY puts every unit back and replies inventory.released")
    void releaseCompensatesExactly() {
        UUID orderId = UUID.randomUUID();
        int before = availableQuantity(LAPTOP);

        command(KafkaTopics.INVENTORY_COMMANDS,
                reserveCommand(orderId, LAPTOP, "CF-LAPTOP-001", 3), orderId);
        await().atMost(SAGA_TIMEOUT).untilAsserted(() ->
                assertThat(reservationRepository.findByOrderId(orderId))
                        .map(InventoryReservation::getStatus)
                        .contains(ReservationStatus.RESERVED));

        try (Consumer<String, Object> replies = consumerFor(KafkaTopics.INVENTORY_RELEASED)) {
            command(KafkaTopics.INVENTORY_COMMANDS, ReleaseInventoryCommand.builder()
                    .orderId(orderId)
                    .orderNumber("CF-IT-" + orderId.toString().substring(0, 8))
                    .reason("PAYMENT_FAILED")
                    .build(), orderId);

            InventoryReleasedEvent reply =
                    awaitMessage(replies, InventoryReleasedEvent.class, SAGA_TIMEOUT);

            assertThat(reply.getOrderId()).isEqualTo(orderId);
            assertThat(reply.getReason()).isEqualTo("PAYMENT_FAILED");
        }

        // Every unit is back where it started: the compensation is exact, not approximate.
        await().atMost(SAGA_TIMEOUT).untilAsserted(() -> {
            assertThat(availableQuantity(LAPTOP)).isEqualTo(before);
            assertThat(reservationRepository.findByOrderId(orderId))
                    .map(InventoryReservation::getStatus)
                    .contains(ReservationStatus.RELEASED);
        });
    }

    @Test
    @DisplayName("CONFIRM_INVENTORY turns the hold into a deduction and replies")
    void confirmWritesTheStockOff() {
        UUID orderId = UUID.randomUUID();
        int availableBefore = availableQuantity(LAPTOP);
        int reservedBefore = reservedQuantity(LAPTOP);

        command(KafkaTopics.INVENTORY_COMMANDS,
                reserveCommand(orderId, LAPTOP, "CF-LAPTOP-001", 2), orderId);
        await().atMost(SAGA_TIMEOUT).untilAsserted(() ->
                assertThat(reservedQuantity(LAPTOP)).isEqualTo(reservedBefore + 2));

        try (Consumer<String, Object> replies = consumerFor(KafkaTopics.INVENTORY_CONFIRMED)) {
            command(KafkaTopics.INVENTORY_COMMANDS, ConfirmInventoryCommand.builder()
                    .orderId(orderId)
                    .orderNumber("CF-IT-" + orderId.toString().substring(0, 8))
                    .build(), orderId);

            InventoryConfirmedEvent reply =
                    awaitMessage(replies, InventoryConfirmedEvent.class, SAGA_TIMEOUT);
            assertThat(reply.getOrderId()).isEqualTo(orderId);
        }

        await().atMost(SAGA_TIMEOUT).untilAsserted(() -> {
            // The hold is gone and the stock stays gone: sold, not merely held.
            assertThat(reservedQuantity(LAPTOP)).isEqualTo(reservedBefore);
            assertThat(availableQuantity(LAPTOP)).isEqualTo(availableBefore - 2);
            assertThat(reservationRepository.findByOrderId(orderId))
                    .map(InventoryReservation::getStatus)
                    .contains(ReservationStatus.CONFIRMED);
        });
    }

    // =====================================================================================
    // The reply rule
    // =====================================================================================

    @Test
    @DisplayName("a re-sent reserve command answers again without reserving twice")
    void resentCommandRepliesAgainWithoutMovingStock() {
        UUID orderId = UUID.randomUUID();
        int before = availableQuantity(LAPTOP);

        command(KafkaTopics.INVENTORY_COMMANDS,
                reserveCommand(orderId, LAPTOP, "CF-LAPTOP-001", 4), orderId);
        await().atMost(SAGA_TIMEOUT).untilAsserted(() ->
                assertThat(availableQuantity(LAPTOP)).isEqualTo(before - 4));

        UUID reservationId = reservationRepository.findByOrderId(orderId).orElseThrow().getId();

        try (Consumer<String, Object> replies = consumerFor(KafkaTopics.INVENTORY_RESERVED)) {
            // A fresh command id, exactly as the orchestrator's timeout retry would send. Staying
            // silent here would deadlock the saga: it re-sent because it never got an answer.
            ReserveInventoryCommand retry = reserveCommand(orderId, LAPTOP, "CF-LAPTOP-001", 4);
            retry.setAttempt(2);
            command(KafkaTopics.INVENTORY_COMMANDS, retry, orderId);

            InventoryReservedEvent reply =
                    awaitMessage(replies, InventoryReservedEvent.class, SAGA_TIMEOUT);

            // Same reservation, answered again.
            assertThat(reply.getReservationId()).isEqualTo(reservationId);
            assertThat(reply.getCausationId()).isEqualTo(retry.getEventId());
        }

        // And not one extra unit moved.
        await().during(java.time.Duration.ofSeconds(3)).atMost(SAGA_TIMEOUT).untilAsserted(() ->
                assertThat(availableQuantity(LAPTOP)).isEqualTo(before - 4));
    }

    @Test
    @DisplayName("releasing an order with no reservation still answers, because a no-op succeeded")
    void releaseWithNothingToUndoStillReplies() {
        UUID orderId = UUID.randomUUID();

        try (Consumer<String, Object> replies = consumerFor(KafkaTopics.INVENTORY_RELEASED)) {
            command(KafkaTopics.INVENTORY_COMMANDS, ReleaseInventoryCommand.builder()
                    .orderId(orderId)
                    .orderNumber("CF-IT-none")
                    .reason("PAYMENT_FAILED")
                    .build(), orderId);

            InventoryReleasedEvent reply =
                    awaitMessage(replies, InventoryReleasedEvent.class, SAGA_TIMEOUT);
            assertThat(reply.getOrderId()).isEqualTo(orderId);
            assertThat(reply.getReservationId()).isNull();
        }
    }

    @Test
    @DisplayName("a redelivered command with the same id does not reserve the stock twice")
    void redeliveryDoesNotDoubleReserve() {
        UUID orderId = UUID.randomUUID();
        int before = availableQuantity(LAPTOP);

        ReserveInventoryCommand cmd = reserveCommand(orderId, LAPTOP, "CF-LAPTOP-001", 4);

        command(KafkaTopics.INVENTORY_COMMANDS, cmd, orderId);
        await().atMost(SAGA_TIMEOUT).untilAsserted(() ->
                assertThat(availableQuantity(LAPTOP)).isEqualTo(before - 4));

        // Exactly the same event id: the idempotency ledger has to swallow it, and it may stay
        // silent because the original reply is already on the bus.
        publish(KafkaTopics.INVENTORY_COMMANDS, cmd);

        await().during(java.time.Duration.ofSeconds(3)).atMost(SAGA_TIMEOUT).untilAsserted(() ->
                assertThat(availableQuantity(LAPTOP)).isEqualTo(before - 4));
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private int availableQuantity(UUID productId) {
        return inventoryItemRepository.findById(productId)
                .map(InventoryItem::getAvailableQuantity)
                .orElseThrow(() -> new AssertionError("No stock row for " + productId));
    }

    private int reservedQuantity(UUID productId) {
        return inventoryItemRepository.findById(productId)
                .map(InventoryItem::getReservedQuantity)
                .orElseThrow(() -> new AssertionError("No stock row for " + productId));
    }

    private static ReserveInventoryCommand reserveCommand(UUID orderId, UUID productId, String sku,
                                                          int quantity) {
        BigDecimal unitPrice = new BigDecimal("1899.00");
        return ReserveInventoryCommand.builder()
                .eventId(UUID.randomUUID())
                .timestamp(Instant.now())
                .orderId(orderId)
                .orderNumber("CF-IT-" + orderId.toString().substring(0, 8))
                .userId(UUID.randomUUID())
                .userEmail("ada@commerceflow.io")
                .totalAmount(unitPrice.multiply(BigDecimal.valueOf(quantity)))
                .currency("EUR")
                .items(List.of(OrderLineItem.builder()
                        .productId(productId)
                        .sku(sku)
                        .productName("Integration test product")
                        .quantity(quantity)
                        .unitPrice(unitPrice)
                        .build()))
                .build();
    }
}
