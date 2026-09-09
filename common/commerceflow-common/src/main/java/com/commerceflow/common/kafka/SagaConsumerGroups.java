package com.commerceflow.common.kafka;

/**
 * Kafka consumer group ids.
 *
 * <p>Also used as the scope of the idempotency ledger, so one listener never suppresses another
 * listener's delivery of the same message.
 *
 * <p>Under orchestration each participant group consumes exactly one command topic, and
 * {@link #ORDER_SERVICE} — the orchestrator — is the only consumer of the reply topics. A
 * participant group subscribed to another participant's topic would mean the pattern had leaked
 * back into choreography.
 */
public final class SagaConsumerGroups {

    /** The orchestrator. Consumes every reply topic; consumes no command topic. */
    public static final String ORDER_SERVICE = "order-service";

    /** Consumes {@code inventory.commands} only. */
    public static final String INVENTORY_SERVICE = "inventory-service";

    /** Consumes {@code payment.commands} only. */
    public static final String PAYMENT_SERVICE = "payment-service";

    /** Consumes {@code notification.send} only. */
    public static final String NOTIFICATION_SERVICE = "notification-service";

    private SagaConsumerGroups() {
        throw new AssertionError("No instances");
    }
}
