package com.commerceflow.notificationservice.entity;

/** Lifecycle of a notification. */
public enum NotificationStatus {

    /** Persisted, not yet handed to a channel. */
    PENDING,

    /** Accepted by the channel. */
    SENT,

    /** The channel rejected it permanently. */
    FAILED
}
