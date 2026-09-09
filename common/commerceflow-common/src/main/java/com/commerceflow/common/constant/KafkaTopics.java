package com.commerceflow.common.constant;

import java.util.List;

/**
 * Kafka topic names used by the orchestrated order saga.
 *
 * <p>Two directions, and the distinction is the whole point of the pattern:
 *
 * <ul>
 *   <li><b>Command topics</b> — the orchestrator telling one named participant to do one thing.
 *       A command has exactly one consumer group and is an instruction, not a fact.
 *   <li><b>Reply / event topics</b> — a participant reporting what happened. The orchestrator is
 *       the only saga consumer; anything else reading them is an observer (audit, analytics) that
 *       the saga does not depend on.
 * </ul>
 *
 * <p>The reply names are the ones fixed by {@code .github/docs/event-flow.md} and remain part of
 * the public integration contract of the platform. The command topics are additive. Neither may
 * be renamed without a versioned migration plan.
 */
public final class KafkaTopics {

    // ---------------------------------------------------------------- commands (orchestrator ->)

    /** Orchestrator -> Inventory: reserve, release or confirm stock for one order. */
    public static final String INVENTORY_COMMANDS = "inventory.commands";

    /** Orchestrator -> Payment: charge the customer for one order. */
    public static final String PAYMENT_COMMANDS = "payment.commands";

    /**
     * Orchestrator -> Notification: deliver one message.
     *
     * <p>Already a command topic before the move to orchestration, and still used by services
     * outside the saga (Auth Service sends the welcome mail here).
     */
    public static final String NOTIFICATION_SEND = "notification.send";

    // ------------------------------------------------------------------- replies (-> orchestrator)

    /** Inventory -> Orchestrator: stock for every line is held. */
    public static final String INVENTORY_RESERVED = "inventory.reserved";

    /** Inventory -> Orchestrator: stock could not be held. Terminal for the forward path. */
    public static final String INVENTORY_FAILED = "inventory.failed";

    /** Inventory -> Orchestrator: a previous reservation has been compensated. */
    public static final String INVENTORY_RELEASED = "inventory.released";

    /** Inventory -> Orchestrator: the hold became a permanent deduction. */
    public static final String INVENTORY_CONFIRMED = "inventory.confirmed";

    /** Inventory -> Orchestrator: sold goods went back on the shelf. */
    public static final String INVENTORY_RESTOCKED = "inventory.restocked";

    /** Payment -> Orchestrator: the charge succeeded. */
    public static final String PAYMENT_COMPLETED = "payment.completed";

    /** Payment -> Orchestrator: the charge was declined. */
    public static final String PAYMENT_FAILED = "payment.failed";

    /** Payment -> Orchestrator: the money was sent back, or could not be. */
    public static final String PAYMENT_REFUNDED = "payment.refunded";

    /** Notification -> Orchestrator: the message was dispatched, or permanently failed. */
    public static final String NOTIFICATION_SENT = "notification.sent";

    // --------------------------------------------------------------------------- domain events

    /**
     * Order Service announcing that an order exists.
     *
     * <p>No saga participant consumes this any more — the orchestrator issues
     * {@link #INVENTORY_COMMANDS} directly. It stays published because it is the documented
     * public contract and the stream external consumers (analytics, BI) subscribe to.
     */
    public static final String ORDER_CREATED = "order.created";

    /** Order Service announcing terminal success. Observers only; the saga does not react to it. */
    public static final String ORDER_COMPLETED = "order.completed";

    /** Order Service announcing terminal failure. Observers only; the saga does not react to it. */
    public static final String ORDER_CANCELLED = "order.cancelled";

    /**
     * Auth Service announcing that a customer asked to be forgotten.
     *
     * <p>An announcement, not a saga step. Each service scrubs what it holds independently, and
     * one that is down catches up when it returns — erasure has a legal deadline measured in
     * weeks, not the seconds a payment does.
     */
    public static final String USER_ERASED = "user.erased";

    /**
     * Order Service asking Payment Service to refund a return.
     *
     * <p>Its own topic rather than {@code payment.commands}, because a return refund is not saga
     * traffic and must not be handled by the compensation path — that one reverses a payment
     * whole and would mark a partially returned order fully refunded.
     */
    public static final String RETURN_REFUND_COMMANDS = "return.refund.commands";

    /** The answer, success or failure. Exactly one consumer: the return service that asked. */
    public static final String RETURN_REFUNDED = "return.refunded";

    /**
     * Order Service asking Inventory Service to put returned goods back on the shelf.
     *
     * <p>Its own topic rather than {@code inventory.commands} for the same reason returns keep
     * their own refund topic: a January backlog of returns must not queue in front of a
     * reservation somebody is waiting on at checkout.
     */
    public static final String RETURN_RESTOCK_COMMANDS = "return.restock.commands";

    /** Suffix appended by the {@code DeadLetterPublishingRecoverer} for poison messages. */
    public static final String DEAD_LETTER_SUFFIX = ".DLT";

    /** Every business topic, in saga order. Used by the topic bootstrap job and by tests. */
    public static final List<String> ALL = List.of(
            ORDER_CREATED,
            INVENTORY_COMMANDS,
            INVENTORY_RESERVED,
            INVENTORY_FAILED,
            PAYMENT_COMMANDS,
            PAYMENT_COMPLETED,
            PAYMENT_FAILED,
            INVENTORY_RELEASED,
            INVENTORY_CONFIRMED,
            INVENTORY_RESTOCKED,
            PAYMENT_REFUNDED,
            ORDER_COMPLETED,
            ORDER_CANCELLED,
            NOTIFICATION_SEND,
            NOTIFICATION_SENT,
            USER_ERASED,
            RETURN_REFUND_COMMANDS,
            RETURN_REFUNDED,
            RETURN_RESTOCK_COMMANDS);

    private KafkaTopics() {
        throw new AssertionError("No instances");
    }
}
