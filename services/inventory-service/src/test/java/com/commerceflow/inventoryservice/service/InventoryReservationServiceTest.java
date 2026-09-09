package com.commerceflow.inventoryservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.commerceflow.common.event.ConfirmInventoryCommand;
import com.commerceflow.common.event.DomainEvent;
import com.commerceflow.common.event.InventoryConfirmedEvent;
import com.commerceflow.common.event.InventoryFailedEvent;
import com.commerceflow.common.event.InventoryReleasedEvent;
import com.commerceflow.common.event.InventoryReservedEvent;
import com.commerceflow.common.event.OrderLineItem;
import com.commerceflow.common.event.ReleaseInventoryCommand;
import com.commerceflow.common.event.ReserveInventoryCommand;
import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.common.idempotency.IdempotencyService;
import com.commerceflow.common.outbox.OutboxService;
import com.commerceflow.inventoryservice.entity.InventoryItem;
import com.commerceflow.inventoryservice.entity.StockLevel;
import com.commerceflow.inventoryservice.entity.Warehouse;
import com.commerceflow.inventoryservice.entity.InventoryReservation;
import com.commerceflow.inventoryservice.entity.ReservationItem;
import com.commerceflow.inventoryservice.entity.ReservationStatus;
import com.commerceflow.inventoryservice.repository.InventoryItemRepository;
import com.commerceflow.inventoryservice.repository.StockLevelRepository;
import com.commerceflow.inventoryservice.repository.WarehouseRepository;
import com.commerceflow.inventoryservice.repository.InventoryReservationRepository;

/**
 * The Inventory participant is the piece that has to be exactly right: it is the only place where
 * stock actually moves, and every branch of it decides whether the saga proceeds or compensates.
 *
 * <p>Each test checks both halves of the participant contract — what happened to the stock, and
 * what the orchestrator was told. A step that does the work silently is as broken as one that
 * does nothing, because the saga stalls either way.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryReservationServiceTest {

    private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111101");
    private static final String SKU = "CF-LAPTOP-001";
    private static final UUID WAREHOUSE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222201");

    @Mock
    private InventoryItemRepository inventoryItemRepository;

    @Mock
    private StockLevelRepository stockLevelRepository;

    @Mock
    private WarehouseRepository warehouseRepository;

    @Mock
    private InventoryReservationRepository reservationRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private OutboxService outboxService;

    @Mock
    private AuditService auditService;

    @Captor
    private ArgumentCaptor<DomainEvent> replyCaptor;

    @Captor
    private ArgumentCaptor<InventoryReservation> reservationCaptor;

    private InventoryReservationService service;

    /** The product-level summary. Derived; see {@link #level} for where the units actually are. */
    private InventoryItem stockItem;

    /** The truth. Ten units in one building. */
    private StockLevel level;

    private Warehouse warehouse;

    @BeforeEach
    void setUp() {
        // A real refresher over the mocked repositories, not a mock of it. The summary rule was
        // moved out of this class, not changed — so these tests should still observe exactly the
        // writes they observed before, and a mock here would hide it if they did not.
        service = new InventoryReservationService(inventoryItemRepository,
                new InventorySummaryRefresher(stockLevelRepository, inventoryItemRepository),
                stockLevelRepository, warehouseRepository, reservationRepository,
                idempotencyService, outboxService, auditService);

        stockItem = InventoryItem.builder()
                .productId(PRODUCT_ID)
                .sku(SKU)
                .availableQuantity(10)
                .reservedQuantity(0)
                .reorderLevel(2)
                .updatedAt(Instant.now())
                .build();

        warehouse = Warehouse.builder()
                .id(WAREHOUSE_ID).code("AMS").name("Amsterdam").countryCode("NL")
                .priority(10).active(true).build();

        level = StockLevel.builder()
                .id(UUID.randomUUID()).warehouseId(WAREHOUSE_ID).productId(PRODUCT_ID)
                .availableQuantity(10).reservedQuantity(0).reorderLevel(2)
                .updatedAt(Instant.now()).build();

        when(idempotencyService.claim(anyString(), any(UUID.class), anyString())).thenReturn(true);
        when(inventoryItemRepository.lockAllByProductIds(anyList())).thenReturn(List.of(stockItem));
        when(inventoryItemRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(stockItem));
        when(stockLevelRepository.lockAllByProductIds(anyList())).thenReturn(List.of(level));
        when(stockLevelRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(level));
        when(warehouseRepository.findByActiveTrueOrderByPriorityAscCodeAsc())
                .thenReturn(List.of(warehouse));
        when(reservationRepository.save(any(InventoryReservation.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(reservationRepository.findByOrderId(any(UUID.class))).thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("RESERVE_INVENTORY")
    class Reserve {

        @Test
        @DisplayName("moves stock from available to reserved and replies inventory.reserved")
        void reservesAvailableStock() {
            ReserveInventoryCommand command = reserveCommand(3);

            service.reserve(command);

            assertThat(stockItem.getAvailableQuantity()).isEqualTo(7);
            assertThat(stockItem.getReservedQuantity()).isEqualTo(3);

            verify(reservationRepository).save(reservationCaptor.capture());
            InventoryReservation reservation = reservationCaptor.getValue();
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(reservation.getItems()).hasSize(1);
            assertThat(reservation.getItems().get(0).getQuantity()).isEqualTo(3);

            InventoryReservedEvent reply = capturedReply(InventoryReservedEvent.class);
            assertThat(reply.getOrderId()).isEqualTo(command.getOrderId());
            assertThat(reply.getReservationId()).isEqualTo(reservation.getId());
            assertThat(reply.getStatus()).isEqualTo("RESERVED");
            // Without the linkage the orchestrator cannot match this to a saga or a step.
            assertThat(reply.getSagaId()).isEqualTo(command.getSagaId());
            assertThat(reply.getCausationId()).isEqualTo(command.getEventId());
        }

        @Test
        @DisplayName("replies inventory.failed and leaves stock untouched when demand exceeds supply")
        void rejectsWhenOutOfStock() {
            service.reserve(reserveCommand(11));

            assertThat(stockItem.getAvailableQuantity()).isEqualTo(10);
            assertThat(stockItem.getReservedQuantity()).isZero();

            InventoryFailedEvent reply = capturedReply(InventoryFailedEvent.class);
            assertThat(reply.getReason()).isEqualTo(InventoryReservationService.REASON_OUT_OF_STOCK);
            assertThat(reply.getUnavailableSkus()).containsExactly(SKU);

            verify(reservationRepository).save(reservationCaptor.capture());
            assertThat(reservationCaptor.getValue().getStatus()).isEqualTo(ReservationStatus.FAILED);
        }

        @Test
        @DisplayName("replies inventory.failed when a product is not in the catalogue")
        void rejectsUnknownProduct() {
            when(inventoryItemRepository.lockAllByProductIds(anyList())).thenReturn(List.of());

            service.reserve(reserveCommand(1));

            assertThat(capturedReply(InventoryFailedEvent.class).getReason())
                    .isEqualTo(InventoryReservationService.REASON_PRODUCT_NOT_FOUND);
        }

        @Test
        @DisplayName("checks a repeated SKU against the summed quantity, not line by line")
        void aggregatesRepeatedLines() {
            ReserveInventoryCommand command = reserveCommand(6);
            command.setItems(List.of(line(6), line(6)));

            service.reserve(command);

            assertThat(stockItem.getAvailableQuantity()).isEqualTo(10);
            capturedReply(InventoryFailedEvent.class);
        }

        @Test
        @DisplayName("a malformed line fails the order instead of poisoning the topic")
        void rejectsMalformedLine() {
            ReserveInventoryCommand command = reserveCommand(1);
            command.setItems(List.of(OrderLineItem.builder().sku(SKU).quantity(0).build()));

            service.reserve(command);

            assertThat(capturedReply(InventoryFailedEvent.class).getReason())
                    .isEqualTo(InventoryReservationService.REASON_EMPTY_ORDER);
        }

        @Test
        @DisplayName("a redelivery of the same command is dropped by the ledger")
        void dropsDuplicateDelivery() {
            when(idempotencyService.claim(anyString(), any(UUID.class), anyString()))
                    .thenReturn(false);

            service.reserve(reserveCommand(3));

            // Silent on purpose: the original delivery committed, so its reply is already out.
            assertThat(stockItem.getAvailableQuantity()).isEqualTo(10);
            verify(outboxService, never()).append(anyString(), anyString(), any(DomainEvent.class));
        }
    }

    @Nested
    @DisplayName("the reply rule")
    class AlwaysReplies {

        @Test
        @DisplayName("a re-sent command answers from the existing reservation without reserving twice")
        void resentCommandRepliesFromExistingReservation() {
            UUID orderId = UUID.randomUUID();
            InventoryReservation existing = reservation(orderId, ReservationStatus.RESERVED, 3);
            when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.of(existing));

            ReserveInventoryCommand retry = reserveCommand(3);
            retry.setOrderId(orderId);
            retry.setSagaId(orderId);
            retry.setAttempt(2);

            service.reserve(retry);

            // No stock moved...
            assertThat(stockItem.getAvailableQuantity()).isEqualTo(10);
            // ...but the orchestrator was answered, which is what stops the saga deadlocking.
            InventoryReservedEvent reply = capturedReply(InventoryReservedEvent.class);
            assertThat(reply.getReservationId()).isEqualTo(existing.getId());
            assertThat(reply.getCausationId()).isEqualTo(retry.getEventId());
        }

        @Test
        @DisplayName("a re-sent command for a reservation that failed answers with the failure again")
        void resentCommandRepliesFailureForFailedReservation() {
            UUID orderId = UUID.randomUUID();
            InventoryReservation failed = reservation(orderId, ReservationStatus.FAILED, 0);
            failed.setReason("OUT_OF_STOCK");
            when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.of(failed));

            ReserveInventoryCommand retry = reserveCommand(3);
            retry.setOrderId(orderId);

            service.reserve(retry);

            assertThat(capturedReply(InventoryFailedEvent.class).getReason())
                    .isEqualTo("OUT_OF_STOCK");
        }

        @Test
        @DisplayName("a reserve for stock that was already given back is a failure, not a success")
        void releasedReservationCannotBeReportedReserved() {
            UUID orderId = UUID.randomUUID();
            when(reservationRepository.findByOrderId(orderId))
                    .thenReturn(Optional.of(reservation(orderId, ReservationStatus.RELEASED, 3)));

            ReserveInventoryCommand retry = reserveCommand(3);
            retry.setOrderId(orderId);

            service.reserve(retry);

            // Reporting success would send the saga into a payment step for stock nobody holds.
            assertThat(capturedReply(InventoryFailedEvent.class).getReason())
                    .isEqualTo(InventoryReservationService.REASON_ALREADY_RELEASED);
        }
    }

    @Nested
    @DisplayName("RELEASE_INVENTORY")
    class Release {

        @Test
        @DisplayName("returns the held stock and replies inventory.released")
        void releasesHeldStock() {
            UUID orderId = UUID.randomUUID();
            level.reserve(3);
            when(reservationRepository.findByOrderId(orderId))
                    .thenReturn(Optional.of(reservation(orderId, ReservationStatus.RESERVED, 3)));

            service.release(releaseCommand(orderId, "PAYMENT_FAILED"));

            assertThat(stockItem.getAvailableQuantity()).isEqualTo(10);
            assertThat(stockItem.getReservedQuantity()).isZero();

            InventoryReleasedEvent reply = capturedReply(InventoryReleasedEvent.class);
            assertThat(reply.getOrderId()).isEqualTo(orderId);
            assertThat(reply.getReason()).isEqualTo("PAYMENT_FAILED");
        }

        @Test
        @DisplayName("a second release moves nothing but still answers")
        void secondReleaseIsANoOpThatStillReplies() {
            UUID orderId = UUID.randomUUID();
            when(reservationRepository.findByOrderId(orderId))
                    .thenReturn(Optional.of(reservation(orderId, ReservationStatus.RELEASED, 3)));

            service.release(releaseCommand(orderId, "PAYMENT_FAILED"));

            assertThat(stockItem.getAvailableQuantity()).isEqualTo(10);
            // Already compensated is still compensated. The orchestrator needs to hear so.
            assertThat(capturedReply(InventoryReleasedEvent.class).getOrderId()).isEqualTo(orderId);
        }

        @Test
        @DisplayName("releasing an order with no reservation answers rather than going quiet")
        void missingReservationStillReplies() {
            UUID orderId = UUID.randomUUID();
            when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.empty());

            service.release(releaseCommand(orderId, "PAYMENT_FAILED"));

            InventoryReleasedEvent reply = capturedReply(InventoryReleasedEvent.class);
            assertThat(reply.getReservationId()).isNull();
        }
    }

    @Nested
    @DisplayName("CONFIRM_INVENTORY")
    class Confirm {

        @Test
        @DisplayName("turns the hold into a permanent deduction and replies")
        void confirmsTheHold() {
            UUID orderId = UUID.randomUUID();
            level.reserve(3);
            when(reservationRepository.findByOrderId(orderId))
                    .thenReturn(Optional.of(reservation(orderId, ReservationStatus.RESERVED, 3)));

            service.confirm(confirmCommand(orderId));

            assertThat(stockItem.getReservedQuantity()).isZero();
            assertThat(stockItem.getAvailableQuantity()).isEqualTo(7);

            InventoryConfirmedEvent reply = capturedReply(InventoryConfirmedEvent.class);
            assertThat(reply.getOrderId()).isEqualTo(orderId);
        }

        @Test
        @DisplayName("confirming an order with no reservation still answers")
        void missingReservationStillReplies() {
            UUID orderId = UUID.randomUUID();
            when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.empty());

            service.confirm(confirmCommand(orderId));

            assertThat(capturedReply(InventoryConfirmedEvent.class).getOrderId()).isEqualTo(orderId);
        }
    }

    @Nested
    @DisplayName("operator release")
    class OperatorRelease {

        @Test
        @DisplayName("puts every unit back and records who did it")
        void releasesByHand() {
            UUID orderId = UUID.randomUUID();
            level.reserve(3);
            when(reservationRepository.findByOrderId(orderId))
                    .thenReturn(Optional.of(reservation(orderId, ReservationStatus.RESERVED, 3)));

            var result = service.releaseManually(orderId, "Saga orphaned by a deploy");

            assertThat(stockItem.getAvailableQuantity()).isEqualTo(10);
            assertThat(stockItem.getReservedQuantity()).isZero();
            assertThat(result.unitsReturned()).isEqualTo(3);
            assertThat(result.reason()).isEqualTo("Saga orphaned by a deploy");

            // Published like any other stock movement, so a manual release is as auditable as a
            // commanded one. The saga id lets a live orchestrator ignore it safely.
            InventoryReleasedEvent published = capturedReply(InventoryReleasedEvent.class);
            assertThat(published.getOrderId()).isEqualTo(orderId);
            assertThat(published.getSagaId()).isEqualTo(orderId);
        }

        @Test
        @DisplayName("defaults the reason rather than recording an empty one")
        void defaultsTheReason() {
            UUID orderId = UUID.randomUUID();
            level.reserve(2);
            when(reservationRepository.findByOrderId(orderId))
                    .thenReturn(Optional.of(reservation(orderId, ReservationStatus.RESERVED, 2)));

            assertThat(service.releaseManually(orderId, "  ").reason())
                    .isEqualTo(InventoryReservationService.REASON_RELEASED_BY_OPERATOR);
        }

        @Test
        @DisplayName("refuses an order with no reservation")
        void unknownOrderIsRejected() {
            UUID orderId = UUID.randomUUID();
            when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.releaseManually(orderId, null))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(outboxService, never()).append(anyString(), anyString(), any(DomainEvent.class));
        }

        @Test
        @DisplayName("refuses a reservation that is no longer holding stock")
        void alreadyReleasedIsRejected() {
            UUID orderId = UUID.randomUUID();
            when(reservationRepository.findByOrderId(orderId))
                    .thenReturn(Optional.of(reservation(orderId, ReservationStatus.CONFIRMED, 3)));

            // Confirmed means sold. Releasing it would invent stock that does not exist.
            assertThatThrownBy(() -> service.releaseManually(orderId, null))
                    .isInstanceOf(ConflictException.class);
            assertThat(stockItem.getAvailableQuantity()).isEqualTo(10);
        }
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private <T extends DomainEvent> T capturedReply(Class<T> type) {
        verify(outboxService).append(anyString(), anyString(), replyCaptor.capture());
        DomainEvent captured = replyCaptor.getValue();
        assertThat(captured).isInstanceOf(type);
        return type.cast(captured);
    }

    private static OrderLineItem line(int quantity) {
        return OrderLineItem.builder()
                .productId(PRODUCT_ID)
                .sku(SKU)
                .productName("CommerceFlow Developer Laptop 14")
                .quantity(quantity)
                .unitPrice(new BigDecimal("1899.00"))
                .build();
    }

    private static ReserveInventoryCommand reserveCommand(int quantity) {
        UUID orderId = UUID.randomUUID();
        return ReserveInventoryCommand.builder()
                .eventId(UUID.randomUUID())
                .eventType("RESERVE_INVENTORY")
                .timestamp(Instant.now())
                .sagaId(orderId)
                .orderId(orderId)
                .orderNumber("CF-20260826-000001")
                .userId(UUID.randomUUID())
                .userEmail("ada@commerceflow.io")
                .totalAmount(new BigDecimal("1899.00").multiply(BigDecimal.valueOf(quantity)))
                .currency("EUR")
                .items(new ArrayList<>(List.of(line(quantity))))
                .build();
    }

    private static ReleaseInventoryCommand releaseCommand(UUID orderId, String reason) {
        return ReleaseInventoryCommand.builder()
                .eventId(UUID.randomUUID())
                .eventType("RELEASE_INVENTORY")
                .timestamp(Instant.now())
                .sagaId(orderId)
                .orderId(orderId)
                .orderNumber("CF-20260826-000001")
                .reason(reason)
                .build();
    }

    private static ConfirmInventoryCommand confirmCommand(UUID orderId) {
        return ConfirmInventoryCommand.builder()
                .eventId(UUID.randomUUID())
                .eventType("CONFIRM_INVENTORY")
                .timestamp(Instant.now())
                .sagaId(orderId)
                .orderId(orderId)
                .orderNumber("CF-20260826-000001")
                .build();
    }

    private InventoryReservation reservation(UUID orderId, ReservationStatus status, int quantity) {
        InventoryReservation reservation = InventoryReservation.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .status(status)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .items(new ArrayList<>())
                .build();
        reservation.addItem(ReservationItem.builder()
                .warehouseId(WAREHOUSE_ID)
                .id(UUID.randomUUID())
                .productId(PRODUCT_ID)
                .sku(SKU)
                .quantity(quantity)
                .build());
        return reservation;
    }
}
