package com.commerceflow.notificationservice.channel;

import java.io.Serial;

/** Raised by a {@link NotificationSender} when a channel refuses a notification. */
public class NotificationDeliveryException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public NotificationDeliveryException(String message) {
        super(message);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
