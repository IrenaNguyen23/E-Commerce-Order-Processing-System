package com.commerceflow.orderservice.saga;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.orderservice.config.OrderProperties;
import com.commerceflow.orderservice.entity.Order;
import com.commerceflow.orderservice.repository.OrderRepository;
import com.commerceflow.orderservice.saga.entity.SagaInstance;
import com.commerceflow.orderservice.saga.entity.SagaState;
import com.commerceflow.orderservice.saga.entity.SagaStep;
import com.commerceflow.orderservice.saga.entity.StepStatus;
import com.commerceflow.orderservice.saga.repository.SagaInstanceRepository;
import com.commerceflow.orderservice.saga.repository.SagaStepLogRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * What the orchestrator does about a step that never answered.
 *
 * <p>One transaction per saga, so a single bad row cannot take a whole sweep down with it and a
 * saga chased successfully stays chased even if the next one throws.
 *
 * <p>Running out of retries does not mean the same thing at every step, and treating it as if it
 * did was a real bug: a reserve step that goes unanswered would leave stock held for an order
 * that will never be placed, and an unanswered release would leave stock held for an order that
 * was already cancelled. Both quietly reduce what the shop can sell, and neither needs a human.
 * {@link SagaStep#onRetriesExhausted()} is where that judgement lives.
 *
 * @see SagaTimeoutMonitor for the schedule that drives this
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SagaRecoveryService {

    private static final List<SagaState> RUNNING =
            List.of(SagaState.STARTED, SagaState.COMPENSATING);

    private final SagaInstanceRepository sagaRepository;
    private final SagaStepLogRepository stepLogRepository;
    private final OrderRepository orderRepository;
    private final OrderSagaOrchestrator orchestrator;
    private final OrderProperties properties;

    /** Ids of sagas whose current step is past its deadline, oldest first, bounded. */
    @Transactional(readOnly = true)
    public List<UUID> findOverdue() {
        return sagaRepository
                .findOverdue(RUNNING, Instant.now(),
                        PageRequest.of(0, properties.getSaga().getScanBatchSize()))
                .stream()
                .map(SagaInstance::getId)
                .toList();
    }

    /** How many sagas are parked waiting for a human. */
    @Transactional(readOnly = true)
    public long countStalled() {
        return sagaRepository.countByState(SagaState.STALLED);
    }

    /**
     * Chases one overdue saga.
     *
     * <p>The deadline is re-checked inside the transaction. Between the scan and this call the
     * reply may well have arrived — that race is normal, and the loser should be this method.
     *
     * @return the outcome, for the caller's metrics
     */
    @Transactional
    public Outcome chase(UUID sagaId) {
        Optional<SagaInstance> found = sagaRepository.findById(sagaId);
        if (found.isEmpty()) {
            return Outcome.GONE;
        }
        SagaInstance saga = found.get();

        if (!saga.getState().isRunning()
                || saga.getStepDeadline() == null
                || saga.getStepDeadline().isAfter(Instant.now())) {
            // The reply landed while we were scanning. Nothing to do, and nothing went wrong.
            return Outcome.ALREADY_MOVED_ON;
        }

        Optional<Order> order = orderRepository.findById(saga.getId());
        if (order.isEmpty()) {
            // Nothing can be rebuilt or unwound without the order it is about.
            log.error("Saga {} has no order; parking it for an operator", sagaId);
            park(saga, "The order this saga belongs to no longer exists");
            return Outcome.STALLED;
        }

        if (saga.getAttempt() < properties.getSaga().getMaxAttempts()) {
            orchestrator.resend(saga, order.get());
            return Outcome.RESENT;
        }
        return exhausted(saga, order.get());
    }

    /**
     * The retry budget is spent. What that means depends entirely on the step.
     *
     * @see SagaStep#onRetriesExhausted()
     */
    private Outcome exhausted(SagaInstance saga, Order order) {
        SagaStep step = saga.getCurrentStep();

        return switch (step.onRetriesExhausted()) {
            case ABANDON -> {
                closeCurrentStep(saga, "Unanswered after " + saga.getAttempt() + " attempt(s)");
                orchestrator.abandon(saga, order);
                yield Outcome.ABANDONED;
            }

            case KEEP_TRYING -> {
                // Deliberately past the budget. A compensation that gives up is the thing that
                // strands stock, so this keeps asking and makes the noise an operator's problem
                // rather than the shop's.
                log.error("Saga {} (order {}) has been retrying {} for {} attempt(s) and is still "
                                + "unanswered. Stock stays held until it succeeds — check the "
                                + "Inventory consumer and its dead-letter topic.",
                        saga.getId(), saga.getOrderNumber(), step, saga.getAttempt());
                orchestrator.resend(saga, order);
                yield Outcome.RESENT_OVERDUE;
            }

            case PARK -> {
                park(saga, "No reply after " + saga.getAttempt() + " attempt(s)");
                yield Outcome.STALLED;
            }
        };
    }

    /**
     * Gives up chasing and parks the saga.
     *
     * <p>Reached only for the steps where the outcome is genuinely unknown. A payment step that
     * has gone quiet may well have taken the customer's money, and cancelling the order on that
     * guess would be a worse failure than the outage that caused it. The saga is left exactly as
     * it is, fully described by its step log, for an operator to resolve.
     */
    private void park(SagaInstance saga, String detail) {
        closeCurrentStep(saga, detail);
        saga.stall();

        log.error("Saga {} (order {}) STALLED at step {} after {} attempt(s). "
                        + "Not resolving automatically: this needs an operator.",
                saga.getId(), saga.getOrderNumber(), saga.getCurrentStep(), saga.getAttempt());
    }

    /** Closes the open step-log row so the history shows why this attempt stopped. */
    private void closeCurrentStep(SagaInstance saga, String detail) {
        if (saga.getCurrentCommandId() == null) {
            return;
        }
        stepLogRepository.findByCommandId(saga.getCurrentCommandId())
                .ifPresent(row -> row.close(StepStatus.TIMED_OUT, null, detail));
    }

    /** What one chase did. */
    public enum Outcome {
        /** The command was sent again, within budget. */
        RESENT,
        /** The command was sent again past its budget, because giving up would strand something. */
        RESENT_OVERDUE,
        /** The saga unwound itself: order cancelled, stock released, nothing charged. */
        ABANDONED,
        /** The outcome is unknown; the saga is parked for a human. */
        STALLED,
        /** The reply arrived between the scan and the chase. */
        ALREADY_MOVED_ON,
        /** The saga no longer exists. */
        GONE
    }
}
