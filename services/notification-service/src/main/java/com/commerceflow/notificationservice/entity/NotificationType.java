package com.commerceflow.notificationservice.entity;

/** What the notification is about; also the template key. */
public enum NotificationType {

    /** Sent once an address has been confirmed — the moment a registration becomes real. */
    USER_WELCOME,

    /** Sent at registration: welcomes the customer and asks them to confirm the address. */
    EMAIL_VERIFICATION,

    /** Carries a short-lived link that lets someone set a new password. */
    PASSWORD_RESET,

    /**
     * Tells a customer their password changed.
     *
     * <p>Not a courtesy. It is the one signal that reaches someone whose account was taken over
     * by an attacker who then reset the password, so it is sent whether or not they asked.
     */
    PASSWORD_CHANGED,

    /** The saga completed successfully. */
    ORDER_CONFIRMED,

    /** The saga compensated, or a person cancelled the order. */
    ORDER_CANCELLED,

    /** The parcel has left us. The first message a customer actually waits for. */
    SHIPMENT_DISPATCHED,

    /** The carrier tried and could not. Says what happens next, because it is a problem. */
    SHIPMENT_ATTEMPTED,

    /** It arrived. */
    SHIPMENT_DELIVERED,

    /** It came back to us. */
    SHIPMENT_RETURNED,

    /**
     * A return the customer asked for, approved.
     *
     * <p>Distinct from {@link #SHIPMENT_RETURNED}, which is a parcel the carrier bounced back
     * without anybody asking. Same word, opposite situations: one is a customer's decision and
     * the other is a failure to deliver.
     */
    RETURN_APPROVED,

    RETURN_REJECTED,

    RETURN_REFUNDED,

    /** Anything driven by a raw notification.send command with no known template. */
    GENERIC
}
