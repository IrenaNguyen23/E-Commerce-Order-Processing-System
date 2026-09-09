package com.commerceflow.inventoryservice.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.event.ConfirmInventoryCommand;
import com.commerceflow.common.event.InventoryConfirmedEvent;
import com.commerceflow.common.event.InventoryFailedEvent;
import com.commerceflow.common.event.InventoryReleasedEvent;
import com.commerceflow.common.event.InventoryReservedEvent;
import com.commerceflow.common.event.InventoryRestockedEvent;
import com.commerceflow.common.event.RestockInventoryCommand;
import com.commerceflow.common.event.OrderLineItem;
import com.commerceflow.common.event.ReleaseInventoryCommand;
import com.commerceflow.common.event.ReserveInventoryCommand;
import com.commerceflow.common.event.SagaCommand;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.idempotency.IdempotencyService;
import com.commerceflow.common.kafka.SagaConsumerGroups;
import com.commerceflow.common.outbox.OutboxService;
import com.commerceflow.inventoryservice.entity.InventoryItem;
import com.commerceflow.inventoryservice.entity.InventoryReservation;
import com.commerceflow.inventoryservice.entity.ReservationItem;
import com.commerceflow.inventoryservice.entity.ReservationStatus;
import com.commerceflow.inventoryservice.entity.StockLevel;
import com.commerceflow.inventoryservice.entity.Warehouse;
import com.commerceflow.inventoryservice.repository.InventoryItemRepository;
import com.commerceflow.inventoryservice.repository.StockLevelRepository;
import com.commerceflow.inventoryservice.repository.WarehouseRepository;
import com.commerceflow.inventoryservice.dto.ReservationReleaseResponse;
import com.commerceflow.inventoryservice.repository.InventoryReservationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Inventory as a saga participant.
 *
 * <p>It carries out the three stock commands the orchestrator can send — reserve, release,
 * confirm — and answers each one. It does not know what happens next, does not listen to Payment
 * or to Order, and has no opinion about whether the order should proceed. That ignorance is the
 * point: the flow lives in the orchestrator, and this service is one step of it.
 *
 * <h2>The reply rule</h2>
 *
 * <p><b>Every command produces exactly one reply, including a command for work already done.</b>
 * A re-sent {@code RESERVE_INVENTORY} for an order that is already reserved answers
 * {@code inventory.reserved} again, with the original reservation id.
 *
 * <p>This is what makes the orchestrator's timeout retry safe. The tempting alternative — noticing
 * the duplicate and returning quietly — deadlocks the saga: the orchestrator re-sends precisely
 * because it never got an answer, so staying silent the second time guarantees it never will.
 * Silence is not idempotence.
 *
 * <p>Every method is one transaction that (a) claims the command in the idempotency ledger,
 * (b) moves stock, and (c) appends the reply to the outbox. Because all three commit together,
 * this service can never move stock without answering for it, or answer for a move it did not
 * make.
 *
 * <p>Reservation reads take a {@code PESSIMISTIC_WRITE} lock in product-id order: two customers
 * racing for the last unit is ordinary traffic, and the fixed lock order rules out deadlocks.
 *
 * <h2>Stock has a location</h2>
 *
 * <p>Every unit lives in a warehouse, and {@code stock_levels} is the truth about where. The
 * product-level {@code inventory_items} row is a <b>summary recomputed from those rows in the same
 * transaction</b> — it exists so a product listing can show availability without summing a
 * warehouse table per tile, and nothing reserves against it.
 *
 * <p>Reserving allocates from named buildings ({@code StockAllocator} decides which), and each
 * reservation row records the building it came from. Releasing, confirming and restocking then act
 * on that same building rather than working one out again — which would be a guess, wrong exactly
 * when stock has moved since.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryReservationService {

    private static final String AGGREGATE_TYPE = "INVENTORY_RESERVATION";
    private static final String GROUP = SagaConsumerGroups.INVENTORY_SERVICE;

    static final String REASON_OUT_OF_STOCK = "OUT_OF_STOCK";
    static final String REASON_PRODUCT_NOT_FOUND = "PRODUCT_NOT_FOUND";
    static final String REASON_EMPTY_ORDER = "EMPTY_ORDER";
    static final String REASON_ALREADY_RELEASED = "RESERVATION_ALREADY_RELEASED";
    static final String REASON_RELEASED_BY_OPERATOR = "RELEASED_BY_OPERATOR";

    private final InventoryItemRepository inventoryItemRepository;
    private final InventorySummaryRefresher summaryRefresher;
    private final StockLevelRepository stockLevelRepository;
    private final WarehouseRepository warehouseRepository;
    private final InventoryReservationRepository reservationRepository;
    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;
    private final AuditService auditService;

    // =====================================================================================
    // RESERVE_INVENTORY
    // =====================================================================================

    /**
     * Holds stock for every line of an order.
     *
     * <p>Answers {@code inventory.reserved} or {@code inventory.failed} — exactly one of them,
     * always, so the saga can never stall on this step.
     */
    @Transactional
    public void reserve(ReserveInventoryCommand command) {
        if (!claim(command)) {
            return;
        }

        Optional<InventoryReservation> existing =
                reservationRepository.findByOrderId(command.getOrderId());
        if (existing.isPresent()) {
            replayReserveOutcome(command, existing.get());
            return;
        }

        List<OrderLineItem> lines = command.getItems();
        if (lines == null || lines.isEmpty() || !isWellFormed(lines)) {
            reject(command, REASON_EMPTY_ORDER, List.of());
            return;
        }

        Map<UUID, Integer> demand = aggregateDemand(lines);
        List<UUID> productIds = demand.keySet().stream().sorted().toList();
        Map<UUID, InventoryItem> stock = lockStock(productIds);

        List<String> missing = new ArrayList<>();
        for (OrderLineItem line : lines) {
            if (!stock.containsKey(line.getProductId())) {
                missing.add(line.getSku());
            }
        }
        if (!missing.isEmpty()) {
            reject(command, REASON_PRODUCT_NOT_FOUND, missing);
            return;
        }

        // Locked in the same fixed order as the summary rows, so two checkouts racing for the
        // last unit queue rather than deadlock.
        Map<UUID, List<StockLevel>> levels = lockLevels(productIds);
        List<Warehouse> usable = warehouseRepository.findByActiveTrueOrderByPriorityAscCodeAsc();

        List<StockAllocator.Allocation> allocations = StockAllocator.allocate(
                demand, levels, usable, command.getDestinationCountry());

        // A product with no allocation could not be covered anywhere. The allocator does not
        // part-fill a line, so this is the only shortfall case there is.
        List<String> unavailable = new ArrayList<>();
        for (UUID productId : demand.keySet()) {
            boolean covered = allocations.stream()
                    .anyMatch(allocation -> allocation.productId().equals(productId));
            if (!covered) {
                unavailable.add(stock.get(productId).getSku());
            }
        }
        if (!unavailable.isEmpty()) {
            reject(command, REASON_OUT_OF_STOCK, unavailable);
            return;
        }

        Map<UUID, StockLevel> levelById = new LinkedHashMap<>();
        levels.values().forEach(list -> list.forEach(level -> levelById.put(level.getId(), level)));

        UUID reservationId = UUID.randomUUID();
        Instant now = Instant.now();
        InventoryReservation reservation = InventoryReservation.builder()
                .id(reservationId)
                .orderId(command.getOrderId())
                .orderNumber(command.getOrderNumber())
                .userId(command.getUserId())
                .status(ReservationStatus.RESERVED)
                .createdAt(now)
                .updatedAt(now)
                .items(new ArrayList<>())
                .build();

        // One row per building used. A line split across two buildings becomes two rows for
        // the same product, which is exactly what it is: two parcels.
        for (StockAllocator.Allocation allocation : allocations) {
            StockLevel level = levelFor(levels, allocation);
            level.reserve(allocation.quantity());

            reservation.addItem(ReservationItem.builder()
                    .id(UUID.randomUUID())
                    .warehouseId(allocation.warehouseId())
                    .productId(allocation.productId())
                    .sku(stock.get(allocation.productId()).getSku())
                    .quantity(allocation.quantity())
                    .build());
        }

        refreshSummaries(productIds);
        reservationRepository.save(reservation);
        replyReserved(command, reservation, lines);

        long buildings = allocations.stream()
                .map(StockAllocator.Allocation::warehouseId)
                .distinct()
                .count();
        log.info("Reserved stock for order {} (reservation {}) from {} building(s)",
                command.getOrderId(), reservationId, buildings);
    }

    /**
     * Answers a re-sent reserve command from the reservation that already exists.
     *
     * <p>The state of that reservation is the answer: it is still holding stock, or it never
     * managed to. Either way the orchestrator gets the same reply it would have got the first
     * time, and no stock moves twice.
     */
    private void replayReserveOutcome(ReserveInventoryCommand command,
                                      InventoryReservation reservation) {
        log.info("Order {} already has reservation {} in state {}; replying from it instead of "
                        + "reserving again (command attempt {})",
                command.getOrderId(), reservation.getId(), reservation.getStatus(),
                command.getAttempt());

        switch (reservation.getStatus()) {
            case RESERVED, CONFIRMED ->
                    // The reserve step did succeed, whatever has happened since.
                    replyReserved(command, reservation, command.getItems());
            case FAILED ->
                    replyFailed(command, reservation.getReason(), List.of());
            case RELEASED ->
                    // Compensated already. Reporting success would send the saga forward into a
                    // payment step for stock nobody is holding.
                    replyFailed(command, REASON_ALREADY_RELEASED, List.of());
        }
    }

    // =====================================================================================
    // RELEASE_INVENTORY  (compensation)
    // =====================================================================================

    /**
     * Gives held stock back.
     *
     * <p>Answers {@code inventory.released} in every case, including when there is nothing to
     * release. A compensation that has nothing to undo has still succeeded, and the orchestrator
     * needs to hear so.
     */
    @Transactional
    public void release(ReleaseInventoryCommand command) {
        if (!claim(command)) {
            return;
        }

        Optional<InventoryReservation> found =
                reservationRepository.findByOrderId(command.getOrderId());

        if (found.isEmpty()) {
            log.warn("No reservation to release for order {}; the compensation is a no-op",
                    command.getOrderId());
            replyReleased(command, null, command.getReason());
            return;
        }

        InventoryReservation reservation = found.get();
        if (!reservation.isHoldingStock()) {
            log.debug("Reservation {} is already {}; nothing to release", reservation.getId(),
                    reservation.getStatus());
            replyReleased(command, reservation.getId(), command.getReason());
            return;
        }

        int units = returnStock(reservation, command.getReason());
        replyReleased(command, reservation.getId(), command.getReason());

        log.info("Released reservation {} for order {}: {} unit(s) back ({})", reservation.getId(),
                command.getOrderId(), units, command.getReason());
    }

    /**
     * Puts every held unit back and closes the reservation.
     *
     * <p>The one place stock goes from held to available. Both callers — the orchestrator's
     * release command and the operator endpoint below — go through here, so the two can never
     * disagree about what releasing means or leave the counters in different states.
     *
     * @return how many units went back, for the log and the operator's response
     */
    private int returnStock(InventoryReservation reservation, String reason) {
        List<UUID> productIds = productIdsOf(reservation);
        lockStock(productIds);
        Map<UUID, List<StockLevel>> levels = lockLevels(productIds);

        int units = 0;
        for (ReservationItem item : reservation.getItems()) {
            // Back into the building it came out of. Choosing one now would be a guess, and it
            // would be wrong precisely when stock has been moved between buildings since.
            StockLevel level = levelFor(levels, item);
            if (level != null) {
                level.release(item.getQuantity());
                units += item.getQuantity();
            }
        }
        refreshSummaries(productIds);
        reservation.markReleased(reason);
        return units;
    }

    // =====================================================================================
    // Operator lever
    // =====================================================================================

    /**
     * Releases a hold by hand, outside the saga.
     *
     * <p>{@link StaleReservationMonitor} reports holds that have outlived any plausible saga but
     * deliberately does not release them, because from inside Inventory a hold whose saga is
     * parked mid-payment looks exactly like one whose saga no longer exists — and releasing the
     * first would put stock back on sale for an order the customer may already have paid for.
     * This is how a person, having checked which of the two it is, acts on the answer.
     *
     * <p>The released event is published with the order id as the saga id, matching what a
     * commanded release would carry. If a saga does still exist, its step guard drops the event
     * unless it happens to be waiting for exactly this — which is the correct outcome either way.
     *
     * @throws ResourceNotFoundException when the order has no reservation
     * @throws ConflictException when the reservation is not holding stock any more
     */
    @Transactional
    public ReservationReleaseResponse releaseManually(UUID orderId, String reason) {
        InventoryReservation reservation = reservationRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESERVATION_NOT_FOUND, "No reservation for order " + orderId));

        if (!reservation.isHoldingStock()) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "Reservation " + reservation.getId() + " is already "
                            + reservation.getStatus() + " and is not holding any stock");
        }

        String recordedReason = reason == null || reason.isBlank()
                ? REASON_RELEASED_BY_OPERATOR
                : reason.trim();

        int units = returnStock(reservation, recordedReason);

        // The one place stock moves because a person decided it should, rather than because
        // the saga said so. Exactly the kind of action somebody asks about later.
        auditService.record("RESERVATION_RELEASED_MANUALLY", "RESERVATION", reservation.getId(),
                units + " unit(s) released for order " + reservation.getOrderNumber()
                        + ": " + recordedReason);

        InventoryReleasedEvent released = InventoryReleasedEvent.builder()
                .orderId(orderId)
                .reservationId(reservation.getId())
                .reason(recordedReason)
                .build();
        released.setSagaId(orderId);
        outboxService.append(AGGREGATE_TYPE, orderId.toString(), released);

        log.warn("Reservation {} for order {} released by an operator: {} unit(s) back ({})",
                reservation.getId(), orderId, units, recordedReason);

        return new ReservationReleaseResponse(reservation.getId(), orderId,
                reservation.getStatus().name(), units, recordedReason);
    }

    // =====================================================================================
    // CONFIRM_INVENTORY
    // =====================================================================================

    /**
     * Turns the hold into a permanent deduction.
     *
     * <p>Answers {@code inventory.confirmed} in every case. Sent only after the customer has been
     * charged, so stock is never written off for an order that was not paid for.
     */
    @Transactional
    public void confirm(ConfirmInventoryCommand command) {
        if (!claim(command)) {
            return;
        }

        Optional<InventoryReservation> found =
                reservationRepository.findByOrderId(command.getOrderId());
        if (found.isEmpty()) {
            log.warn("No reservation to confirm for order {}", command.getOrderId());
            replyConfirmed(command, null);
            return;
        }

        InventoryReservation reservation = found.get();
        if (!reservation.isHoldingStock()) {
            log.debug("Reservation {} is {}; nothing left to confirm", reservation.getId(),
                    reservation.getStatus());
            replyConfirmed(command, reservation.getId());
            return;
        }

        List<UUID> confirmedProducts = productIdsOf(reservation);
        lockStock(confirmedProducts);
        Map<UUID, List<StockLevel>> confirmedLevels = lockLevels(confirmedProducts);

        reservation.getItems().forEach(item -> {
            StockLevel level = levelFor(confirmedLevels, item);
            if (level != null) {
                level.confirm(item.getQuantity());
            }
        });
        refreshSummaries(confirmedProducts);
        reservation.markConfirmed();

        replyConfirmed(command, reservation.getId());

        log.info("Confirmed reservation {} for order {}", reservation.getId(),
                command.getOrderId());
    }

    // =====================================================================================
    // RESTOCK_INVENTORY  (compensation, after the sale was already written off)
    // =====================================================================================

    /**
     * Puts sold goods back on the shelf.
     *
     * <p>Answers {@code inventory.restocked} in every case, including when there is nothing to
     * return — a compensation with no work to do has still succeeded, and the orchestrator needs
     * to hear so.
     *
     * <p>Only a {@code CONFIRMED} reservation can be returned. One still {@code RESERVED} was
     * never written off, so it needs releasing rather than restocking, and restocking it would
     * credit units that were never deducted.
     */
    @Transactional
    public void restock(RestockInventoryCommand command) {
        if (!claim(command)) {
            return;
        }

        Optional<InventoryReservation> found =
                reservationRepository.findByOrderId(command.getOrderId());

        if (found.isEmpty()) {
            log.warn("No reservation to restock for order {}; the compensation is a no-op",
                    command.getOrderId());
            replyRestocked(command, null, 0);
            return;
        }

        InventoryReservation reservation = found.get();
        if (!reservation.isSold()) {
            // Already returned, or never sold in the first place. Either way there is nothing to
            // put back, and doing it anyway would invent stock.
            log.info("Reservation {} is {}; nothing to restock", reservation.getId(),
                    reservation.getStatus());
            replyRestocked(command, reservation.getId(), 0);
            return;
        }

        List<UUID> restockedProducts = productIdsOf(reservation);
        lockStock(restockedProducts);
        Map<UUID, List<StockLevel>> restockedLevels = lockLevels(restockedProducts);

        int units = 0;
        for (ReservationItem item : reservation.getItems()) {
            StockLevel level = levelFor(restockedLevels, item);
            if (level != null) {
                level.restock(item.getQuantity());
                units += item.getQuantity();
            }
        }
        refreshSummaries(restockedProducts);
        reservation.markReturned(command.getReason());

        replyRestocked(command, reservation.getId(), units);

        log.info("Restocked {} unit(s) from cancelled order {} (reservation {}, {})", units,
                command.getOrderId(), reservation.getId(), command.getReason());
    }

    // =====================================================================================
    // Replies
    // =====================================================================================

    private void replyReserved(ReserveInventoryCommand command, InventoryReservation reservation,
                               List<OrderLineItem> lines) {
        InventoryReservedEvent reply = InventoryReservedEvent.builder()
                .orderId(command.getOrderId())
                .orderNumber(command.getOrderNumber())
                .reservationId(reservation.getId())
                .userId(command.getUserId())
                .userEmail(command.getUserEmail())
                .totalAmount(command.getTotalAmount())
                .currency(command.getCurrency())
                .items(lines)
                .status(ReservationStatus.RESERVED.name())
                .build();
        reply.replyTo(command);
        outboxService.append(AGGREGATE_TYPE, reservation.getId().toString(), reply);
    }

    private void replyFailed(ReserveInventoryCommand command, String reason, List<String> skus) {
        InventoryFailedEvent reply = InventoryFailedEvent.builder()
                .orderId(command.getOrderId())
                .orderNumber(command.getOrderNumber())
                .userId(command.getUserId())
                .userEmail(command.getUserEmail())
                .reason(reason)
                .unavailableSkus(skus)
                .build();
        reply.replyTo(command);
        outboxService.append(AGGREGATE_TYPE, command.getOrderId().toString(), reply);
    }

    private void replyReleased(ReleaseInventoryCommand command, UUID reservationId, String reason) {
        InventoryReleasedEvent reply = InventoryReleasedEvent.builder()
                .orderId(command.getOrderId())
                .reservationId(reservationId)
                .reason(reason)
                .build();
        reply.replyTo(command);
        outboxService.append(AGGREGATE_TYPE, command.getOrderId().toString(), reply);
    }

    private void replyRestocked(RestockInventoryCommand command, UUID reservationId, int units) {
        InventoryRestockedEvent reply = InventoryRestockedEvent.builder()
                .orderId(command.getOrderId())
                .reservationId(reservationId)
                .unitsRestocked(units)
                .build();
        reply.replyTo(command);
        outboxService.append(AGGREGATE_TYPE, command.getOrderId().toString(), reply);
    }

    private void replyConfirmed(ConfirmInventoryCommand command, UUID reservationId) {
        InventoryConfirmedEvent reply = InventoryConfirmedEvent.builder()
                .orderId(command.getOrderId())
                .reservationId(reservationId)
                .build();
        reply.replyTo(command);
        outboxService.append(AGGREGATE_TYPE, command.getOrderId().toString(), reply);
    }

    /** Records the failed attempt and answers with the reason. */
    private void reject(ReserveInventoryCommand command, String reason, List<String> skus) {
        Instant now = Instant.now();
        reservationRepository.save(InventoryReservation.builder()
                .id(UUID.randomUUID())
                .orderId(command.getOrderId())
                .orderNumber(command.getOrderNumber())
                .userId(command.getUserId())
                .status(ReservationStatus.FAILED)
                .reason(reason)
                .createdAt(now)
                .updatedAt(now)
                .items(new ArrayList<>())
                .build());

        replyFailed(command, reason, skus);

        log.warn("Cannot reserve stock for order {}: {} {}", command.getOrderId(), reason, skus);
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private Map<UUID, InventoryItem> lockStock(List<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, InventoryItem> byProduct = new LinkedHashMap<>();
        inventoryItemRepository.lockAllByProductIds(productIds)
                .forEach(item -> byProduct.put(item.getProductId(), item));
        return byProduct;
    }

    /**
     * Locks every warehouse row for a set of products, grouped by product.
     *
     * <p>Same fixed order as {@link #lockStock}, and taken after it, so a transaction that needs
     * both always takes them the same way round.
     */
    private Map<UUID, List<StockLevel>> lockLevels(List<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<StockLevel>> byProduct = new LinkedHashMap<>();
        stockLevelRepository.lockAllByProductIds(productIds)
                .forEach(level -> byProduct
                        .computeIfAbsent(level.getProductId(), key -> new ArrayList<>())
                        .add(level));
        return byProduct;
    }

    private static StockLevel levelFor(Map<UUID, List<StockLevel>> levels,
            StockAllocator.Allocation allocation) {
        return levels.getOrDefault(allocation.productId(), List.of()).stream()
                .filter(level -> level.getWarehouseId().equals(allocation.warehouseId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Allocated from a warehouse row that is not locked: "
                                + allocation.warehouseId()));
    }

    /**
     * The row a reservation item's units are held in, or {@code null}.
     *
     * <p>Null rather than an exception: a warehouse row can genuinely have gone — a building
     * closed, a product delisted from it — and refusing to release in that case would strand the
     * rest of the reservation to protect a row that no longer exists.
     */
    private static StockLevel levelFor(Map<UUID, List<StockLevel>> levels, ReservationItem item) {
        List<StockLevel> candidates = levels.getOrDefault(item.getProductId(), List.of());
        if (item.getWarehouseId() == null) {
            // A reservation from before stock had a location. Its units went into the single
            // building the migration created, so there is exactly one row to put them back in.
            return candidates.size() == 1 ? candidates.get(0) : null;
        }
        return candidates.stream()
                .filter(level -> level.getWarehouseId().equals(item.getWarehouseId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Recomputes the product-level totals from the warehouse rows.
     *
     * <p>Called in the same transaction as every stock movement, which is what makes the summary
     * a cache rather than a second opinion. Recomputed from scratch rather than adjusted by the
     * same delta: an incrementally maintained total is correct until one path forgets, and then
     * it is quietly wrong with nothing to compare it against — and this particular total is what
     * a customer is shown when deciding whether something is in stock.
     */
    private void refreshSummaries(List<UUID> productIds) {
        // Returns need the same rule, so it lives in one place. Two copies of a summing rule is
        // how the two drift apart, and whichever one is wrong is then wrong invisibly.
        summaryRefresher.refresh(productIds);
    }

    private static List<UUID> productIdsOf(InventoryReservation reservation) {
        return reservation.getItems().stream()
                .map(ReservationItem::getProductId)
                .sorted()
                .toList();
    }

    /**
     * Sums the quantities per product so an order that lists the same SKU twice is checked and
     * reserved once, against the total.
     */
    private static Map<UUID, Integer> aggregateDemand(List<OrderLineItem> lines) {
        Map<UUID, Integer> demand = new LinkedHashMap<>();
        lines.stream()
                .sorted(Comparator.comparing(OrderLineItem::getProductId))
                .forEach(line -> demand.merge(line.getProductId(),
                        line.getQuantity() == null ? 0 : line.getQuantity(), Integer::sum));
        return demand;
    }

    /**
     * A line without a product or with a non-positive quantity cannot be reserved and cannot be
     * fixed by retrying, so it fails the whole order immediately rather than poisoning the topic.
     */
    private static boolean isWellFormed(List<OrderLineItem> lines) {
        return lines.stream().allMatch(line ->
                line != null
                        && line.getProductId() != null
                        && line.getQuantity() != null
                        && line.getQuantity() > 0);
    }

    /**
     * Claims the command for processing.
     *
     * <p>A {@code false} here means this exact command was already handled and its reply is
     * already in the outbox — so returning without replying is correct. It is not the same
     * situation as a fresh command for work that happens to be done, which must still be
     * answered.
     */
    private boolean claim(SagaCommand command) {
        return idempotencyService.claim(GROUP, command.getEventId(), command.getEventType());
    }
}
