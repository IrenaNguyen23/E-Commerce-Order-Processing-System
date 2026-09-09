package com.commerceflow.orderservice.saga;

import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.orderservice.config.OrderProperties;
import com.commerceflow.orderservice.saga.entity.SagaState;
import com.commerceflow.orderservice.saga.repository.SagaInstanceRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Removes the step-by-step history of sagas that finished long ago.
 *
 * <h2>Only finished ones, and never a parked one</h2>
 *
 * <p>{@code COMPLETED} and {@code COMPENSATED} only. A {@code STALLED} saga is waiting for a
 * person, and its step log is the entire record of what it was doing when it stopped — deleting
 * that would leave an operator with a parked order and no way to find out why.
 *
 * <p>The age test is on {@code updatedAt}, not {@code createdAt}, so a saga that took three days
 * to finish is measured from when it actually finished.
 *
 * <h2>What survives</h2>
 *
 * <p>The <b>order</b> does. This removes the saga's working notes, not the thing it produced: the
 * order, its lines, its snapshot, its payment and its shipments are all still there, and they are
 * what an accountant, a customer or a dispute actually needs.
 *
 * <p>The step log answers a different and shorter-lived question — "why did this take four
 * attempts last Tuesday" — which nobody asks about an order from two years ago.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaHistorySweeper {

    private static final List<SagaState> FINISHED =
            List.of(SagaState.COMPLETED, SagaState.COMPENSATED);

    private final SagaInstanceRepository sagas;
    private final OrderProperties properties;

    /**
     * Daily, at four in the morning.
     *
     * <p>Deliberately not hourly. Nothing here is urgent — a saga log that lives a day longer than
     * its retention costs a few kilobytes, and a sweep competing with checkout traffic costs more.
     */
    @Scheduled(cron = "${commerceflow.order.saga-history-sweep-cron:0 15 4 * * *}")
    @Transactional
    public void sweep() {
        Instant cutoff = Instant.now().minus(properties.getSagaHistoryRetention());

        // Deletes the saga rows; the step log follows by cascade. Doing it the other way round
        // would leave instances with no history, which reads as data loss rather than tidying.
        int removed = sagas.deleteFinishedBefore(FINISHED, cutoff);

        if (removed > 0) {
            log.info("Removed {} finished saga(s) and their step logs, older than {}",
                    removed, cutoff);
        }
    }
}
