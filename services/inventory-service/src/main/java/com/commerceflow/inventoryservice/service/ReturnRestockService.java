package com.commerceflow.inventoryservice.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.event.OrderLineItem;
import com.commerceflow.common.event.RestockReturnedGoodsCommand;
import com.commerceflow.common.idempotency.IdempotencyService;
import com.commerceflow.inventoryservice.entity.InventoryReservation;
import com.commerceflow.inventoryservice.entity.ReservationItem;
import com.commerceflow.inventoryservice.entity.StockLevel;
import com.commerceflow.inventoryservice.repository.InventoryReservationRepository;
import com.commerceflow.inventoryservice.repository.StockLevelRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Putting goods a customer sent back onto the shelf.
 *
 * <h2>Deliberately not {@link InventoryReservationService#restock}</h2>
 *
 * <p>That method serves saga compensation: it restocks every item in a reservation and marks the
 * reservation returned. Correct when a whole order is being unwound; wrong here twice over. A
 * customer returning one of three items would have all three put back on sale, and the reservation
 * would be closed so a second return weeks later would silently do nothing.
 *
 * <h2>Units go back where they came from</h2>
 *
 * <p>The original reservation records which building each line was taken from. Returning stock to
 * a different warehouse would leave both wrong: one short, one over, and a picker sent to an empty
 * shelf.
 *
 * <h2>Never more than went out</h2>
 *
 * <p>A returned quantity is capped at what the reservation actually took. Order Service already
 * enforces this against its own lines, so exceeding it means the two disagree — which is worth
 * refusing loudly rather than turning into invented stock.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReturnRestockService {

    static final String CONSUMER_GROUP = "inventory-service-returns";

    private final InventoryReservationRepository reservations;
    private final StockLevelRepository stockLevels;
    private final InventorySummaryRefresher summaryRefresher;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;

    @Transactional
    public void restock(RestockReturnedGoodsCommand command) {
        // Keyed on the event, and the return id is on the command for the log. A republished
        // command must not put the same units back twice — that would invent stock, which is the
        // mirror image of overselling and just as hard to notice.
        if (!idempotencyService.claim(CONSUMER_GROUP, command.getEventId(),
                command.getEventType())) {
            return;
        }

        Optional<InventoryReservation> found =
                reservations.findByOrderId(command.getOrderId());

        if (found.isEmpty()) {
            log.error("No reservation for order {}; return {} cannot be restocked. {} unit(s) are "
                            + "physically back and not on the shelf.",
                    command.getOrderId(), command.getReturnId(), totalUnits(command));
            return;
        }

        InventoryReservation reservation = found.get();

        // Which building each product came from, from the reservation itself.
        Map<UUID, ReservationItem> byProduct = new HashMap<>();
        for (ReservationItem item : reservation.getItems()) {
            byProduct.put(item.getProductId(), item);
        }

        List<UUID> touched = new ArrayList<>();
        int restocked = 0;

        for (OrderLineItem line : command.getLines()) {
            ReservationItem original = byProduct.get(line.getProductId());
            if (original == null) {
                log.error("Return {} sends back product {} which order {} never reserved. Skipped.",
                        command.getReturnId(), line.getProductId(), command.getOrderId());
                continue;
            }

            int units = Math.min(line.getQuantity(), original.getQuantity());
            if (units < line.getQuantity()) {
                log.error("Return {} sends back {} of product {} but only {} were reserved. "
                                + "Restocking {} — the two services disagree and somebody should "
                                + "find out why.",
                        command.getReturnId(), line.getQuantity(), line.getProductId(),
                        original.getQuantity(), units);
            }

            StockLevel level = stockLevels
                    .findByWarehouseIdAndProductId(original.getWarehouseId(), line.getProductId())
                    .orElse(null);

            if (level == null) {
                // The building may have been closed since the order shipped. The units are real
                // and somebody has to place them, so this is loud rather than silent.
                log.error("No stock level for product {} in warehouse {}; {} unit(s) from return "
                                + "{} are not on any shelf.",
                        line.getProductId(), original.getWarehouseId(), units,
                        command.getReturnId());
                continue;
            }

            level.restock(units);
            stockLevels.save(level);
            touched.add(line.getProductId());
            restocked += units;
        }

        // The product-level figure is a summary of the warehouse rows, so it is recomputed rather
        // than adjusted — the same rule as everywhere else stock moves.
        summaryRefresher.refresh(touched);

        auditService.recordSystem("RETURN_RESTOCKED", "RETURN", command.getReturnId(),
                "Put " + restocked + " unit(s) back on sale from order "
                        + command.getOrderNumber() + " (" + command.getReason() + ")");

        log.info("Restocked {} unit(s) from return {} on order {}", restocked,
                command.getReturnId(), command.getOrderNumber());
    }

    private static int totalUnits(RestockReturnedGoodsCommand command) {
        return command.getLines() == null ? 0
                : command.getLines().stream().mapToInt(OrderLineItem::getQuantity).sum();
    }
}
