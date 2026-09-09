package com.commerceflow.orderservice.saga;

import java.util.List;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * The clock the saga runs against.
 *
 * <p>The orchestrator knows what it asked for and of whom, so a participant going quiet is a
 * specific, actionable fact rather than an order that has merely stopped changing. A late step
 * gets its command re-sent; a step that has stopped answering altogether gets parked with its
 * whole history intact, for a human.
 *
 * <p>Two metrics, and the distinction matters when writing an alert:
 *
 * <ul>
 *   <li>{@code commerceflow.saga.step.resent} — a counter. Some of this is normal under load.
 *   <li>{@code commerceflow.saga.abandoned} — a counter. Sagas that unwound themselves because
 *       their reserve step went unanswered. Every one of these is a customer who did not get
 *       their order, so it should be near zero even though it is handled.
 *   <li>{@code commerceflow.saga.stalled} — a gauge. Anything above zero is a human's problem.
 * </ul>
 */
@Slf4j
@Component
public class SagaTimeoutMonitor {

    private final SagaRecoveryService recoveryService;
    private final Counter resentCounter;
    private final Counter abandonedCounter;

    public SagaTimeoutMonitor(SagaRecoveryService recoveryService, MeterRegistry meterRegistry) {
        this.recoveryService = recoveryService;
        this.resentCounter = Counter.builder("commerceflow.saga.step.resent")
                .description("Saga commands re-sent because the step did not answer in time")
                .register(meterRegistry);
        this.abandonedCounter = Counter.builder("commerceflow.saga.abandoned")
                .description("Sagas unwound automatically after their reserve step went unanswered")
                .register(meterRegistry);

        meterRegistry.gauge("commerceflow.saga.stalled", recoveryService,
                service -> service.countStalled());
    }

    /**
     * Sweeps for overdue steps.
     *
     * <p>Each saga is chased in its own transaction and its own try/catch: one poisoned row must
     * not stop the other ninety-nine from being recovered.
     */
    @Scheduled(
            fixedDelayString = "${commerceflow.order.saga.scan-interval-ms:30000}",
            initialDelayString = "${commerceflow.order.saga.scan-interval-ms:30000}")
    public void chaseOverdueSteps() {
        List<UUID> overdue;
        try {
            overdue = recoveryService.findOverdue();
        } catch (Exception ex) {
            log.error("Saga timeout scan failed", ex);
            return;
        }
        if (overdue.isEmpty()) {
            return;
        }

        log.info("{} saga step(s) are past their deadline", overdue.size());

        int resent = 0;
        int abandoned = 0;
        int stalled = 0;
        for (UUID sagaId : overdue) {
            try {
                switch (recoveryService.chase(sagaId)) {
                    case RESENT, RESENT_OVERDUE -> {
                        resentCounter.increment();
                        resent++;
                    }
                    case ABANDONED -> {
                        abandonedCounter.increment();
                        abandoned++;
                    }
                    case STALLED -> stalled++;
                    // The reply won the race, or the saga is gone. Both are fine.
                    case ALREADY_MOVED_ON, GONE -> { }
                }
            } catch (Exception ex) {
                // Usually an optimistic-lock failure against the reply that just landed. The next
                // sweep sorts it out, and there is nothing for an operator to do about it.
                log.warn("Could not chase saga {}: {}", sagaId, ex.getMessage());
            }
        }

        if (stalled > 0) {
            log.error("{} saga(s) exhausted their retries and are parked for an operator", stalled);
        }
        log.info("Saga sweep: {} re-sent, {} abandoned, {} parked, {} resolved themselves",
                resent, abandoned, stalled, overdue.size() - resent - abandoned - stalled);
    }
}
