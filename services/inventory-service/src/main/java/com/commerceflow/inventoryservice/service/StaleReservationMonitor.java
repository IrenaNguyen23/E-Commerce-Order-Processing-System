package com.commerceflow.inventoryservice.service;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.commerceflow.inventoryservice.config.InventoryProperties;
import com.commerceflow.inventoryservice.entity.InventoryReservation;
import com.commerceflow.inventoryservice.entity.ReservationStatus;
import com.commerceflow.inventoryservice.repository.InventoryReservationRepository;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Finds stock that has been held far longer than any order should take, and says so.
 *
 * <p>A healthy hold lives for seconds. One whose saga had to be chased lives for minutes. A hold
 * still sitting on stock hours later belongs to something that went wrong, and until someone acts
 * on it those units are invisible to every customer — the shop is quietly smaller than it thinks.
 *
 * <h2>Why this reports and does not release</h2>
 *
 * <p>Because from inside Inventory the two cases that produce a stale hold are indistinguishable,
 * and they want opposite treatment:
 *
 * <ul>
 *   <li>Its saga no longer exists — a deployment cut over mid-flight, or the order was purged.
 *       Nothing will ever confirm or release it, and it should go back on sale.
 *   <li>Its saga is parked mid-payment, waiting for a human. <b>The customer may already have
 *       been charged.</b> Releasing it puts sold stock back on the shelf and oversells it.
 * </ul>
 *
 * <p>Inventory cannot tell them apart: it has no visibility into saga state, and asking for it
 * synchronously from a sweeper would make this service depend on Order Service being up in order
 * to do housekeeping. So it surfaces the number and names the orders, and
 * {@code POST /api/reservations/{orderId}/release} is how a person acts once they have checked
 * which case it is.
 *
 * <p>The sagas that <em>can</em> be resolved without a human already are, by the orchestrator: an
 * unanswered reserve step unwinds itself, and an unanswered release keeps retrying rather than
 * giving up. What reaches this monitor is the residue those two rules cannot safely handle.
 */
@Slf4j
@Component
public class StaleReservationMonitor {

    private final InventoryReservationRepository reservationRepository;
    private final InventoryProperties properties;

    private volatile long staleCount;

    public StaleReservationMonitor(InventoryReservationRepository reservationRepository,
                                   InventoryProperties properties,
                                   MeterRegistry meterRegistry) {
        this.reservationRepository = reservationRepository;
        this.properties = properties;

        meterRegistry.gauge("commerceflow.inventory.reservations.stale", this,
                monitor -> monitor.staleCount);
    }

    /**
     * Sweeps for holds older than the configured window.
     *
     * <p>Counted in full, listed in part: the gauge has to be accurate for alerting, but a bad
     * outage could leave thousands stale and a log line naming all of them helps nobody.
     */
    @Scheduled(
            fixedDelayString = "${commerceflow.inventory.reservation.scan-interval-ms:600000}",
            initialDelayString = "${commerceflow.inventory.reservation.scan-interval-ms:600000}")
    public void reportStaleReservations() {
        try {
            Instant cutoff = Instant.now().minus(properties.getReservation().getStaleAfter());

            long total = reservationRepository.countByStatusAndCreatedAtBefore(
                    ReservationStatus.RESERVED, cutoff);
            this.staleCount = total;

            if (total == 0) {
                return;
            }

            List<InventoryReservation> sample =
                    reservationRepository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                            ReservationStatus.RESERVED, cutoff,
                            PageRequest.of(0, properties.getReservation().getScanBatchSize()));

            log.error("{} reservation(s) have been holding stock for longer than {}. "
                            + "Check each order's saga before releasing — a saga parked at the "
                            + "payment step may already have been charged. Oldest {}: {}",
                    total, properties.getReservation().getStaleAfter(), sample.size(),
                    sample.stream().map(InventoryReservation::getOrderNumber).toList());

        } catch (Exception ex) {
            // The sweep is housekeeping. Failing it must never take the service down, and the
            // next pass ten minutes later will report the same thing.
            log.error("Stale-reservation sweep failed", ex);
        }
    }

    /** How many holds were stale at the last sweep. Exposed for tests. */
    long staleCount() {
        return staleCount;
    }
}
