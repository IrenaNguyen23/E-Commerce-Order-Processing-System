package com.commerceflow.orderservice.saga;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.event.ConfirmInventoryCommand;
import com.commerceflow.common.event.DomainEvent;
import com.commerceflow.common.event.InventoryConfirmedEvent;
import com.commerceflow.common.event.InventoryFailedEvent;
import com.commerceflow.common.event.InventoryReleasedEvent;
import com.commerceflow.common.event.InventoryReservedEvent;
import com.commerceflow.common.event.NotificationSendEvent;
import com.commerceflow.common.event.NotificationSentEvent;
import com.commerceflow.common.event.OrderCancelledEvent;
import com.commerceflow.common.event.OrderCompletedEvent;
import com.commerceflow.common.event.PaymentCompletedEvent;
import com.commerceflow.common.event.InventoryRestockedEvent;
import com.commerceflow.common.event.PaymentFailedEvent;
import com.commerceflow.common.event.PaymentRefundedEvent;
import com.commerceflow.common.event.RefundPaymentCommand;
import com.commerceflow.common.event.RestockInventoryCommand;
import com.commerceflow.common.event.ProcessPaymentCommand;
import com.commerceflow.common.event.ReleaseInventoryCommand;
import com.commerceflow.common.event.ReserveInventoryCommand;
import com.commerceflow.common.idempotency.IdempotencyService;
import com.commerceflow.common.kafka.SagaConsumerGroups;
import com.commerceflow.common.outbox.OutboxEvent;
import com.commerceflow.common.outbox.OutboxService;
import com.commerceflow.orderservice.config.OrderProperties;
import com.commerceflow.orderservice.entity.Order;
import com.commerceflow.orderservice.entity.OrderStatus;
import com.commerceflow.orderservice.mapper.OrderMapper;
import com.commerceflow.orderservice.repository.OrderRepository;
import com.commerceflow.orderservice.promotion.CouponService;
import com.commerceflow.orderservice.saga.entity.SagaInstance;
import com.commerceflow.orderservice.saga.entity.SagaState;
import com.commerceflow.orderservice.saga.entity.SagaStep;
import com.commerceflow.orderservice.saga.entity.SagaStepLog;
import com.commerceflow.orderservice.saga.entity.StockUndo;
import com.commerceflow.orderservice.saga.entity.StepStatus;
import com.commerceflow.orderservice.saga.repository.SagaInstanceRepository;
import com.commerceflow.orderservice.saga.repository.SagaStepLogRepository;
import com.commerceflow.orderservice.service.OrderProjectionService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * The order saga orchestrator.
 *
 * <p>One component decides the whole flow. It sends a command, waits for the reply, and from that
 * reply decides the next command — or, if the reply is a failure, which of the completed steps
 * have to be undone. No participant knows what comes after it, and no participant listens to
 * another participant.
 *
 * <p>Every handler here is one transaction spanning four things: the idempotency claim, the saga
 * row, the order aggregate with its read-model projection, and the outbox row carrying the next
 * command. They commit together or not at all, which is what makes the saga survive a crash at
 * any point — the state and the instruction that follows from it can never disagree.
 *
 * <h2>The two rules that make this safe under redelivery</h2>
 *
 * <ol>
 *   <li><b>A reply is only acted on when it names the step the saga is waiting for.</b> Anything
 *       else is late, superseded, or a duplicate that slipped past the ledger, and is dropped.
 *       This is what stops a {@code payment.failed} that arrives after the order completed from
 *       cancelling a paid order.
 *   <li><b>Participants always answer, even for work they have already done.</b> That is what
 *       makes re-sending a command on timeout safe, and it is stated as a contract on each
 *       participant rather than left to chance.
 * </ol>
 *
 * <p>A reply is accepted even when its {@code causationId} points at an earlier attempt of the
 * current step. The step is answered either way, and refusing a slow first reply in favour of a
 * re-send that may never come would trade a working saga for a tidier audit trail.
 *
 * @see SagaTimeoutMonitor for what happens when a reply never arrives
 */
@Slf4j
@Service
public class OrderSagaOrchestrator {

    private static final String AGGREGATE_TYPE = "ORDER";
    private static final String GROUP = SagaConsumerGroups.ORDER_SERVICE;

    /** Compensation reason recorded on the reservation when a charge is declined. */
    static final String REASON_PAYMENT_FAILED = "PAYMENT_FAILED";

    /**
     * Compensation reason when the reserve step never answered.
     *
     * <p>Deliberately not {@code OUT_OF_STOCK}: we do not know whether stock was available. All
     * we know is that Inventory stopped replying, and the customer is owed a message that says
     * so rather than one that invents a cause.
     */
    static final String REASON_INVENTORY_TIMED_OUT = "INVENTORY_TIMED_OUT";

    /** Recorded on the reservation and the payment when a person cancels an order. */
    static final String REASON_CANCELLED_BY_CUSTOMER = "CANCELLED_BY_CUSTOMER";

    private final OrderRepository orderRepository;
    private final SagaInstanceRepository sagaRepository;
    private final SagaStepLogRepository stepLogRepository;
    private final OrderProjectionService projectionService;
    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;
    private final CouponService couponService;
    private final OrderMapper orderMapper;
    private final OrderProperties properties;

    private final Counter completedCounter;
    private final Counter compensatedCounter;

    public OrderSagaOrchestrator(OrderRepository orderRepository,
                                 SagaInstanceRepository sagaRepository,
                                 SagaStepLogRepository stepLogRepository,
                                 OrderProjectionService projectionService,
                                 IdempotencyService idempotencyService,
                                 OutboxService outboxService,
                                 CouponService couponService,
                                 OrderMapper orderMapper,
                                 OrderProperties properties,
                                 MeterRegistry meterRegistry) {
        this.orderRepository = orderRepository;
        this.sagaRepository = sagaRepository;
        this.stepLogRepository = stepLogRepository;
        this.projectionService = projectionService;
        this.idempotencyService = idempotencyService;
        this.outboxService = outboxService;
        this.couponService = couponService;
        this.orderMapper = orderMapper;
        this.properties = properties;
        this.completedCounter = Counter.builder("commerceflow.saga.finished")
                .tag("outcome", "completed")
                .description("Order sagas that ran every forward step to the end")
                .register(meterRegistry);
        this.compensatedCounter = Counter.builder("commerceflow.saga.finished")
                .tag("outcome", "compensated")
                .description("Order sagas that failed and were cleanly undone")
                .register(meterRegistry);
    }

    // =====================================================================================
    // Start
    // =====================================================================================

    /**
     * Opens the saga for a freshly placed order and issues its first command.
     *
     * <p>Called from {@code OrderCommandService.placeOrder} inside that method's transaction, so
     * an order can never exist without a saga to drive it. This is the one place the orchestrator
     * is invoked in-process rather than from a reply; everything after this point is message
     * driven.
     */
    public SagaInstance start(Order order) {
        Instant now = Instant.now();
        SagaInstance saga = SagaInstance.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .userId(order.getUserId())
                .userEmail(order.getUserEmail())
                .state(SagaState.STARTED)
                .attempt(1)
                .createdAt(now)
                .updatedAt(now)
                .build();
        sagaRepository.save(saga);

        send(saga, SagaStep.RESERVE_INVENTORY, reserveCommand(order, 1), 1);

        log.info("Saga {} started for order {}", saga.getId(), order.getOrderNumber());
        return saga;
    }

    /**
     * Cancels an order, and unwinds whatever it had already done.
     *
     * <p>Called in-process from {@code OrderCancellationService}, inside its transaction, so the
     * order and the saga can never disagree about whether a cancellation happened.
     *
     * <p><b>The order is cancelled immediately, before any of the undoing.</b> It will not be
     * fulfilled, and that is knowable right now; making the customer watch "processing" while a
     * refund clears would be reporting our internal progress as if it were their order's status.
     * Whether the money has come back is the payment's business, and the payment says so itself.
     *
     * <p>What happens next depends on how far the order got, and the two branches are not
     * interchangeable:
     *
     * <ul>
     *   <li><b>Paid</b> — refund first, then give the stock back. Money before goods, because a
     *       customer chasing a refund is a worse outcome than a unit that reappears a second late.
     *   <li><b>Not paid</b> — nothing was charged, so there is only the stock.
     * </ul>
     */
    public void cancel(SagaInstance saga, Order order, String reason) {
        StockUndo undo = order.getStatus() == OrderStatus.COMPLETED
                ? StockUndo.RESTOCK
                : StockUndo.RELEASE;

        boolean paid = saga.getPaymentId() != null;

        if (order.getStatus().isTerminal()) {
            order.cancelAfterCompletion(SagaStep.NOTIFY_CUSTOMER.domain(), reason);
        } else {
            order.recordFailure(SagaStep.NOTIFY_CUSTOMER.domain(), reason);
            order.transitionTo(OrderStatus.CANCELLED);
        }
        projectionService.project(order);

        outboxService.append(AGGREGATE_TYPE, order.getId().toString(),
                OrderCancelledEvent.builder()
                        .orderId(order.getId())
                        .orderNumber(order.getOrderNumber())
                        .userId(order.getUserId())
                        .userEmail(order.getUserEmail())
                        .totalAmount(order.getTotalAmount())
                        .currency(order.getCurrency())
                        .failedStep(SagaStep.NOTIFY_CUSTOMER.domain())
                        .reason(reason)
                        .build());

        saga.reopenForCancellation(reason, undo);

        if (paid) {
            send(saga, SagaStep.REFUND_PAYMENT, refundCommand(order, saga, reason, 1), 1);
        } else {
            send(saga, undo.step(), stockUndoCommand(order, undo, reason, 1), 1);
        }

        log.warn("Order {} cancelled ({}). Undoing: {}{}", order.getOrderNumber(), reason,
                paid ? "refund then " : "", undo);
    }

    // =====================================================================================
    // Replies — cancellation
    // =====================================================================================

    /**
     * The money is back, or could not be sent. Either way the stock still has to come home.
     *
     * <p>A failed refund does not stop the saga. The goods are already cancelled and the customer
     * is already owed — halting here would strand the stock as well as the money, and leave two
     * problems where there was one. It is logged as an error and the payment stays
     * {@code COMPLETED}, which is what puts it in front of a human.
     */
    @Transactional
    public void onPaymentRefunded(PaymentRefundedEvent reply) {
        onReply(reply, SagaStep.REFUND_PAYMENT, (saga, order) -> {
            closeStep(saga, reply, reply.isSuccess() ? StepStatus.SUCCEEDED : StepStatus.FAILED,
                    reply.isSuccess() ? null : reply.getFailureReason());

            if (!reply.isSuccess()) {
                log.error("Refund failed for order {}: {}. The customer is owed {} {} and this "
                                + "needs a human — the cancellation continues regardless.",
                        saga.getOrderNumber(), reply.getFailureReason(), reply.getAmount(),
                        reply.getCurrency());
            }

            StockUndo undo = saga.getStockUndo() == null ? StockUndo.RELEASE : saga.getStockUndo();
            send(saga, undo.step(),
                    stockUndoCommand(order, undo, REASON_CANCELLED_BY_CUSTOMER, 1), 1);
        });
    }

    /** Sold goods are back on the shelf. Tell the customer it is done. */
    @Transactional
    public void onInventoryRestocked(InventoryRestockedEvent reply) {
        onReply(reply, SagaStep.RESTOCK_INVENTORY, (saga, order) -> {
            closeStep(saga, reply, StepStatus.SUCCEEDED,
                    reply.getUnitsRestocked() + " unit(s) returned");
            sendCancellationNotice(saga, order, saga.getFailureReason());
        });
    }

    // =====================================================================================
    // Replies — step 1, reserve inventory
    // =====================================================================================

    /** Stock is held. Advance to the payment step. */
    @Transactional
    public void onInventoryReserved(InventoryReservedEvent reply) {
        onReply(reply, SagaStep.RESERVE_INVENTORY, (saga, order) -> {
            closeStep(saga, reply, StepStatus.SUCCEEDED, null);
            saga.setReservationId(reply.getReservationId());

            if (order.transitionTo(OrderStatus.INVENTORY_RESERVED)) {
                projectionService.project(order);
            }
            send(saga, SagaStep.PROCESS_PAYMENT, paymentCommand(order, saga, 1), 1);
        });
    }

    /**
     * Stock could not be held.
     *
     * <p>Nothing to undo: the reservation never happened. That is precisely why this is the first
     * step — the cheapest failure is the one that happens before any money moves.
     */
    @Transactional
    public void onInventoryFailed(InventoryFailedEvent reply) {
        onReply(reply, SagaStep.RESERVE_INVENTORY, (saga, order) -> {
            closeStep(saga, reply, StepStatus.FAILED, reply.getReason());
            saga.beginCompensation(SagaStep.RESERVE_INVENTORY, reply.getReason());

            cancelOrder(order, SagaStep.RESERVE_INVENTORY, reply.getReason());
            sendCancellationNotice(saga, order, reply.getReason());
        });
    }

    // =====================================================================================
    // Replies — step 2, payment
    // =====================================================================================

    /** The customer has been charged. Turn the hold into a permanent deduction. */
    @Transactional
    public void onPaymentCompleted(PaymentCompletedEvent reply) {
        onReply(reply, SagaStep.PROCESS_PAYMENT, (saga, order) -> {
            closeStep(saga, reply, StepStatus.SUCCEEDED, null);
            saga.setPaymentId(reply.getPaymentId());

            order.setPaymentId(reply.getPaymentId());
            if (order.transitionTo(OrderStatus.PAID)) {
                projectionService.project(order);
            }
            send(saga, SagaStep.CONFIRM_INVENTORY, confirmCommand(order, 1), 1);
        });
    }

    /**
     * The charge was declined.
     *
     * <p>The order is cancelled here, at the moment the decision is made, rather than after the
     * stock has gone back: the customer's screen should not keep saying "processing" while an
     * internal cleanup runs. The compensation follows immediately after.
     */
    @Transactional
    public void onPaymentFailed(PaymentFailedEvent reply) {
        onReply(reply, SagaStep.PROCESS_PAYMENT, (saga, order) -> {
            closeStep(saga, reply, StepStatus.FAILED, reply.getReason());
            saga.beginCompensation(SagaStep.PROCESS_PAYMENT, reply.getReason());

            cancelOrder(order, SagaStep.PROCESS_PAYMENT, reply.getReason());
            send(saga, SagaStep.RELEASE_INVENTORY,
                    releaseCommand(order, REASON_PAYMENT_FAILED, 1), 1);
        });
    }

    // =====================================================================================
    // Replies — step 3, confirm / release inventory
    // =====================================================================================

    /** The hold is now a permanent deduction; the order is complete. */
    @Transactional
    public void onInventoryConfirmed(InventoryConfirmedEvent reply) {
        onReply(reply, SagaStep.CONFIRM_INVENTORY, (saga, order) -> {
            closeStep(saga, reply, StepStatus.SUCCEEDED, null);

            if (order.transitionTo(OrderStatus.COMPLETED)) {
                projectionService.project(order);
                outboxService.append(AGGREGATE_TYPE, order.getId().toString(),
                        OrderCompletedEvent.builder()
                                .orderId(order.getId())
                                .orderNumber(order.getOrderNumber())
                                .userId(order.getUserId())
                                .userEmail(order.getUserEmail())
                                .paymentId(saga.getPaymentId())
                                .totalAmount(order.getTotalAmount())
                                .currency(order.getCurrency())
                                // Carried so a subscriber knows what was bought without calling
                                // back here for it. Inventory uses this to record who may leave
                                // a verified review.
                                .items(orderMapper.toEventLines(order))
                                .build());
                log.info("Order {} COMPLETED (payment {})", order.getOrderNumber(),
                        saga.getPaymentId());
            }
            sendConfirmationNotice(saga, order);
        });
    }

    /** The stock is back. Tell the customer why their order stopped. */
    @Transactional
    public void onInventoryReleased(InventoryReleasedEvent reply) {
        onReply(reply, SagaStep.RELEASE_INVENTORY, (saga, order) -> {
            closeStep(saga, reply, StepStatus.SUCCEEDED, reply.getReason());
            sendCancellationNotice(saga, order, saga.getFailureReason());
        });
    }

    // =====================================================================================
    // Replies — step 4, notify
    // =====================================================================================

    /**
     * The customer has been told. The saga is over.
     *
     * <p>A notification that could not be delivered still closes the saga. An undeliverable email
     * is a delivery problem, recorded as one by Notification Service; leaving a paid order
     * looking unfinished because of it would be worse.
     */
    @Transactional
    public void onNotificationSent(NotificationSentEvent reply) {
        onReply(reply, SagaStep.NOTIFY_CUSTOMER, (saga, order) -> {
            closeStep(saga, reply, StepStatus.SUCCEEDED, reply.getStatus());
            saga.finish();

            if (saga.getState() == SagaState.COMPENSATED) {
                compensatedCounter.increment();
                log.warn("Saga {} compensated: order {} cancelled at {} ({})", saga.getId(),
                        saga.getOrderNumber(), saga.getFailedStep(), saga.getFailureReason());
            } else {
                completedCounter.increment();
                log.info("Saga {} completed for order {}", saga.getId(), saga.getOrderNumber());
            }
        });
    }

    // =====================================================================================
    // Command construction — also used by the timeout monitor to re-send
    // =====================================================================================

    ReserveInventoryCommand reserveCommand(Order order, int attempt) {
        return ReserveInventoryCommand.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                // Carried so Inventory can ship from a building in the customer's own country
                // where it has the choice. Optional to Inventory: without it the allocation is
                // still correct, only less good.
                .destinationCountry(order.getShippingCountry())
                .attempt(attempt)
                .userId(order.getUserId())
                .userEmail(order.getUserEmail())
                .totalAmount(order.getTotalAmount())
                .currency(order.getCurrency())
                .items(orderMapper.toEventLines(order))
                .build();
    }

    ProcessPaymentCommand paymentCommand(Order order, SagaInstance saga, int attempt) {
        return ProcessPaymentCommand.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .attempt(attempt)
                .reservationId(saga.getReservationId())
                .userId(order.getUserId())
                .userEmail(order.getUserEmail())
                .amount(order.getTotalAmount())
                .currency(order.getCurrency())
                .build();
    }

    ConfirmInventoryCommand confirmCommand(Order order, int attempt) {
        return ConfirmInventoryCommand.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .attempt(attempt)
                .build();
    }

    ReleaseInventoryCommand releaseCommand(Order order, String reason, int attempt) {
        return ReleaseInventoryCommand.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .attempt(attempt)
                .reason(reason)
                .build();
    }

    /**
     * Why this saga is giving stock back, recorded on the reservation for the audit trail.
     *
     * <p>Derived from the step that failed rather than stored, because there are exactly two ways
     * to reach a release and the failed step already distinguishes them.
     */
    private static String releaseReason(SagaInstance saga) {
        return saga.getFailedStep() == SagaStep.PROCESS_PAYMENT
                ? REASON_PAYMENT_FAILED
                : REASON_INVENTORY_TIMED_OUT;
    }

    RefundPaymentCommand refundCommand(Order order, SagaInstance saga, String reason, int attempt) {
        return RefundPaymentCommand.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .attempt(attempt)
                .paymentId(saga.getPaymentId())
                .amount(order.getTotalAmount())
                .currency(order.getCurrency())
                .reason(reason)
                .build();
    }

    /** The right undo for how far this order got. See {@link StockUndo}. */
    DomainEvent stockUndoCommand(Order order, StockUndo undo, String reason, int attempt) {
        return undo == StockUndo.RESTOCK
                ? RestockInventoryCommand.builder()
                        .orderId(order.getId())
                        .orderNumber(order.getOrderNumber())
                        .attempt(attempt)
                        .reason(reason)
                        .build()
                : releaseCommand(order, reason, attempt);
    }

    NotificationSendEvent notificationCommand(SagaInstance saga, Order order) {
        return saga.getState() == SagaState.COMPENSATING
                ? cancellationNotice(saga, order, saga.getFailureReason())
                : confirmationNotice(saga, order);
    }

    // =====================================================================================
    // Internals
    // =====================================================================================

    /** What a reply handler does once the saga and the order have been loaded and checked. */
    @FunctionalInterface
    private interface SagaAction {
        void apply(SagaInstance saga, Order order);
    }

    /**
     * The shared preamble of every reply handler: claim, load, check, act.
     *
     * <p>An unknown saga is dropped rather than retried. It means the reply belongs to another
     * deployment, to a message outside the order saga (Auth Service's welcome mail arrives on
     * {@code notification.sent} too), or to an order that has been purged — and redelivering it
     * will not change any of those.
     */
    private void onReply(DomainEvent reply, SagaStep expected, SagaAction action) {
        UUID sagaId = reply.getSagaId();
        if (sagaId == null) {
            log.debug("{} {} carries no sagaId; not part of an order saga", reply.getEventType(),
                    reply.getEventId());
            return;
        }
        if (!idempotencyService.claim(GROUP, reply.getEventId(), reply.getEventType())) {
            return;
        }

        Optional<SagaInstance> found = sagaRepository.findById(sagaId);
        if (found.isEmpty()) {
            log.warn("Received {} for unknown saga {}", reply.getEventType(), sagaId);
            return;
        }
        SagaInstance saga = found.get();

        if (!saga.isAwaiting(expected)) {
            // Late, superseded, or an outcome the saga has already moved past. Dropping it is the
            // rule that keeps a stray payment.failed from cancelling an order that completed.
            log.info("Dropping {} for saga {}: state={} currentStep={}, expected {}",
                    reply.getEventType(), sagaId, saga.getState(), saga.getCurrentStep(), expected);
            return;
        }

        Optional<Order> order = orderRepository.findById(saga.getId());
        if (order.isEmpty()) {
            log.error("Saga {} has no order; parking it for an operator", sagaId);
            saga.stall();
            return;
        }

        action.apply(saga, order.get());
    }

    /**
     * Writes a command to the outbox and starts the clock on its reply.
     *
     * <p>The outbox assigns the {@code eventId}, so the step log records the identifier the
     * participant will actually echo back as {@code causationId}.
     */
    private void send(SagaInstance saga, SagaStep step, DomainEvent command, int attempt) {
        command.setSagaId(saga.getId());
        OutboxEvent row = outboxService.append(AGGREGATE_TYPE, saga.getId().toString(), command);

        stepLogRepository.save(SagaStepLog.sent(saga.getId(), step, row.getEventId(), attempt));
        saga.awaitReply(step, row.getEventId(), stepTimeout());

        log.debug("Saga {} -> {} (command {}, attempt {})", saga.getId(), step, row.getEventId(),
                attempt);
    }

    /**
     * Unwinds a saga whose current step stopped answering, without waiting for a human.
     *
     * <p>Package private, and called only for a step whose {@link SagaStep#onRetriesExhausted()}
     * policy is {@code ABANDON} — which today means only the reserve step. That restriction is
     * the whole safety argument: no payment command has been sent, so nothing can have been
     * charged, and the order can be cancelled and the stock given back with no ambiguity at all.
     *
     * <p>The release is sent even though we do not know whether a reservation exists. It is
     * cheap, it is idempotent, and a participant with nothing to undo answers anyway — which is
     * exactly why the reply rule is written the way it is.
     */
    void abandon(SagaInstance saga, Order order) {
        SagaStep step = saga.getCurrentStep();

        saga.beginCompensation(step, REASON_INVENTORY_TIMED_OUT);
        cancelOrder(order, step, REASON_INVENTORY_TIMED_OUT);
        send(saga, SagaStep.RELEASE_INVENTORY,
                releaseCommand(order, REASON_INVENTORY_TIMED_OUT, 1), 1);

        log.error("Saga {} (order {}) abandoned: {} went unanswered. The order is cancelled and "
                        + "the stock is being released. Nothing was charged.",
                saga.getId(), saga.getOrderNumber(), step);
    }

    /**
     * Re-sends the outstanding command of an overdue step.
     *
     * <p>Package private: only {@link SagaTimeoutMonitor} calls it, and only after the deadline
     * has passed. Safe because every participant is obliged to answer a command it has already
     * carried out with the same reply it gave the first time.
     */
    void resend(SagaInstance saga, Order order) {
        SagaStep step = saga.getCurrentStep();
        int attempt = saga.getAttempt() + 1;

        DomainEvent command = switch (step) {
            case RESERVE_INVENTORY -> reserveCommand(order, attempt);
            case PROCESS_PAYMENT -> paymentCommand(order, saga, attempt);
            case CONFIRM_INVENTORY -> confirmCommand(order, attempt);
            case RELEASE_INVENTORY -> releaseCommand(order, releaseReason(saga), attempt);
            case REFUND_PAYMENT ->
                    refundCommand(order, saga, REASON_CANCELLED_BY_CUSTOMER, attempt);
            case RESTOCK_INVENTORY -> RestockInventoryCommand.builder()
                    .orderId(order.getId())
                    .orderNumber(order.getOrderNumber())
                    .attempt(attempt)
                    .reason(REASON_CANCELLED_BY_CUSTOMER)
                    .build();
            case NOTIFY_CUSTOMER -> notificationCommand(saga, order);
        };

        command.setSagaId(saga.getId());
        OutboxEvent row = outboxService.append(AGGREGATE_TYPE, saga.getId().toString(), command);

        stepLogRepository.save(SagaStepLog.sent(saga.getId(), step, row.getEventId(), attempt));
        saga.retryReply(row.getEventId(), stepTimeout());

        log.warn("Saga {} re-sent {} (command {}, attempt {}) after no reply within {}",
                saga.getId(), step, row.getEventId(), attempt, stepTimeout());
    }

    /**
     * Closes the step-log row the reply answers.
     *
     * <p>Matched on {@code causationId}, so a reply to a superseded attempt closes its own row
     * and the log shows which send actually came back rather than crediting the latest one.
     *
     * <p>Falls back to the outstanding command when there is no causation. That happens when the
     * reply was not caused by a command at all — an operator paying a stuck order by hand through
     * the REST endpoint. The step it answers is still the one the saga is waiting on.
     */
    private void closeStep(SagaInstance saga, DomainEvent reply, StepStatus outcome, String detail) {
        UUID commandId = reply.getCausationId() != null
                ? reply.getCausationId()
                : saga.getCurrentCommandId();
        if (commandId == null) {
            return;
        }
        stepLogRepository.findByCommandId(commandId)
                .ifPresent(row -> row.close(outcome, reply.getEventId(), detail));
    }

    /** Moves the order to CANCELLED and announces it, unless it has already finished. */
    private void cancelOrder(Order order, SagaStep step, String reason) {
        if (order.getStatus().isTerminal()) {
            log.debug("Order {} is already {}; nothing to cancel", order.getOrderNumber(),
                    order.getStatus());
            return;
        }

        // The order's failedStep is a published API enum (INVENTORY / PAYMENT), not the
        // orchestrator's internal step name. Leaking RESERVE_INVENTORY here would break every
        // client that reads it and tie the REST contract to the shape of the state machine.
        order.recordFailure(step.domain(), reason);
        order.transitionTo(OrderStatus.CANCELLED);
        projectionService.project(order);

        outboxService.append(AGGREGATE_TYPE, order.getId().toString(),
                OrderCancelledEvent.builder()
                        .orderId(order.getId())
                        .orderNumber(order.getOrderNumber())
                        .userId(order.getUserId())
                        .userEmail(order.getUserEmail())
                        .totalAmount(order.getTotalAmount())
                        .currency(order.getCurrency())
                        .failedStep(step.domain())
                        .reason(reason)
                        .build());

        // The discount code goes back. A declined card is not a spent coupon, and a customer
        // who retries with the same code has to find it still works — otherwise a failed payment
        // silently costs them their discount and looks, from outside, exactly like a code that
        // never worked.
        couponService.release(order.getId());

        log.warn("Order {} CANCELLED at step {}: {}", order.getOrderNumber(), step, reason);
    }

    private void sendConfirmationNotice(SagaInstance saga, Order order) {
        send(saga, SagaStep.NOTIFY_CUSTOMER, confirmationNotice(saga, order), 1);
    }

    private void sendCancellationNotice(SagaInstance saga, Order order, String reason) {
        send(saga, SagaStep.NOTIFY_CUSTOMER, cancellationNotice(saga, order, reason), 1);
    }

    private NotificationSendEvent confirmationNotice(SagaInstance saga, Order order) {
        Map<String, String> params = new HashMap<>();
        params.put("orderNumber", order.getOrderNumber());
        params.put("totalAmount", String.valueOf(order.getTotalAmount()));
        params.put("currency", order.getCurrency());
        params.put("paymentId", String.valueOf(saga.getPaymentId()));

        return NotificationSendEvent.builder()
                .userId(order.getUserId())
                .recipient(order.getUserEmail())
                .templateCode("ORDER_CONFIRMED")
                .subject("Your order " + order.getOrderNumber() + " is confirmed")
                .params(params)
                .referenceId(order.getId())
                .build();
    }

    private NotificationSendEvent cancellationNotice(SagaInstance saga, Order order, String reason) {
        Map<String, String> params = new HashMap<>();
        params.put("orderNumber", order.getOrderNumber());
        params.put("failedStep", stepLabel(saga.getFailedStep()));
        params.put("reason", humanise(reason));
        params.put("refundLine", refundLine(saga));

        return NotificationSendEvent.builder()
                .userId(order.getUserId())
                .recipient(order.getUserEmail())
                .templateCode("ORDER_CANCELLED")
                .subject("Your order " + order.getOrderNumber() + " could not be completed")
                .params(params)
                .referenceId(order.getId())
                .build();
    }

    /**
     * What to tell the customer about their money.
     *
     * <p>This sentence used to be fixed text in the template reading "You have not been charged".
     * That is true of an order cancelled before the payment step and is the worst possible thing
     * to say to somebody who is owed a refund — which, since orders can be cancelled after
     * completion, is now a real case.
     *
     * <p>The saga knows which it is: a payment id exists only once money has moved.
     */
    private static String refundLine(SagaInstance saga) {
        return saga.getPaymentId() == null
                ? "You have not been charged."
                : "We are refunding what you paid. It can take a few days to appear on your "
                        + "statement.";
    }

    private Duration stepTimeout() {
        return properties.getSaga().getStepTimeout();
    }

    /** The customer-facing name of a step, lower case, for the cancellation email. */
    private static String stepLabel(SagaStep step) {
        return step == null ? "processing" : step.domain().toLowerCase(Locale.ROOT);
    }

    /** Turns a machine reason such as {@code INSUFFICIENT_FUNDS} into readable prose. */
    private static String humanise(String reason) {
        if (reason == null || reason.isBlank()) {
            return "an unexpected problem";
        }
        return reason.replace('_', ' ').toLowerCase(Locale.ROOT);
    }
}
