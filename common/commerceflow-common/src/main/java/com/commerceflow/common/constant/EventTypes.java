package com.commerceflow.common.constant;

/**
 * Logical message type names.
 *
 * <p>These names are written to the Kafka {@code __TypeId__} header instead of a Java class name,
 * so the wire format stays independent of the JVM package layout and can be consumed by
 * non-Java clients.
 *
 * <p>Commands are imperative ({@code RESERVE_INVENTORY}) and replies are past tense
 * ({@code INVENTORY_RESERVED}). Reading a topic dump, the mood of the verb tells you which
 * direction the message was travelling.
 */
public final class EventTypes {

    // ------------------------------------------------------------------------------- commands
    public static final String RESERVE_INVENTORY = "RESERVE_INVENTORY";
    public static final String RELEASE_INVENTORY = "RELEASE_INVENTORY";
    public static final String CONFIRM_INVENTORY = "CONFIRM_INVENTORY";
    public static final String PROCESS_PAYMENT = "PROCESS_PAYMENT";
    public static final String REFUND_PAYMENT = "REFUND_PAYMENT";
    public static final String RESTOCK_INVENTORY = "RESTOCK_INVENTORY";
    public static final String NOTIFICATION_SEND = "NOTIFICATION_SEND";

    // -------------------------------------------------------------------------------- replies
    public static final String INVENTORY_RESERVED = "INVENTORY_RESERVED";
    public static final String INVENTORY_FAILED = "INVENTORY_FAILED";
    public static final String INVENTORY_RELEASED = "INVENTORY_RELEASED";
    public static final String INVENTORY_CONFIRMED = "INVENTORY_CONFIRMED";
    public static final String INVENTORY_RESTOCKED = "INVENTORY_RESTOCKED";
    public static final String PAYMENT_COMPLETED = "PAYMENT_COMPLETED";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String PAYMENT_REFUNDED = "PAYMENT_REFUNDED";
    public static final String NOTIFICATION_SENT = "NOTIFICATION_SENT";

    // ------------------------------------------------------------------------- domain events
    public static final String ORDER_CREATED = "ORDER_CREATED";
    public static final String ORDER_COMPLETED = "ORDER_COMPLETED";
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";

    /** A customer asked to be forgotten. Every service scrubs what it holds under that id. */
    public static final String USER_ERASED = "USER_ERASED";

    /** Refund the lines a customer sent back. Not saga compensation — see the command class. */
    public static final String REFUND_RETURN = "REFUND_RETURN";

    /** What came of it, success or failure. */
    public static final String RETURN_REFUNDED = "RETURN_REFUNDED";

    /** Put goods a customer sent back on the shelf. Not saga compensation. */
    public static final String RESTOCK_RETURNED_GOODS = "RESTOCK_RETURNED_GOODS";

    private EventTypes() {
        throw new AssertionError("No instances");
    }
}
