package com.commerceflow.notificationservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.commerceflow.notificationservice.entity.NotificationChannel;

import lombok.Getter;
import lombok.Setter;

/** Notification Service tuning knobs. */
@Getter
@Setter
@ConfigurationProperties(prefix = "commerceflow.notification")
public class NotificationProperties {

    /** Channel used when an event does not name one. */
    private NotificationChannel defaultChannel = NotificationChannel.EMAIL;

    /** From address quoted in the rendered message. */
    private String sender = "no-reply@commerceflow.io";

    /** Delivery attempts before a notification is parked as FAILED. */
    private int maxRetries = 3;

    @org.springframework.boot.context.properties.NestedConfigurationProperty
    private Email email = new Email();

    /** SMTP delivery. Off by default so the stack runs with no mail server at all. */
    @Getter
    @Setter
    public static class Email {

        /**
         * Turns real SMTP delivery on.
         *
         * <p>Off by default, and that default matters: {@code docker compose up} has to give a
         * working system to someone with no mail server and no credentials. With this off,
         * messages go to the structured log exactly as before and every flow still completes.
         */
        private boolean enabled;

        /** Envelope sender. Must be an address the SMTP server is willing to send as. */
        private String from = "no-reply@commerceflow.io";

        /** Display name shown to the recipient. */
        private String fromName = "CommerceFlow";

        /** Where replies go, when it is not the from address. Optional. */
        private String replyTo;
    }

    /**
     * How long a delivered notification is kept.
     *
     * <p>A year. The row is a delivery record — this address was told this thing at this time —
     * not the message itself, which the customer has in their inbox.
     *
     * <p>Applies only to notifications that were actually sent. A failed one is a customer who was
     * never told something and is kept until somebody has dealt with it; sweeping by age alone
     * would quietly delete the evidence of every message the platform failed to deliver.
     */
    private java.time.Duration retention = java.time.Duration.ofDays(365);
}
