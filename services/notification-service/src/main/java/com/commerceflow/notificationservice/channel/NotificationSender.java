package com.commerceflow.notificationservice.channel;

import com.commerceflow.notificationservice.entity.Notification;
import com.commerceflow.notificationservice.entity.NotificationChannel;

/**
 * A delivery channel.
 *
 * <p>One implementation per channel, selected at runtime by {@link #supports(NotificationChannel)}.
 * Wiring in a real SMTP or SMS provider means adding a bean, not editing the saga.
 */
public interface NotificationSender {

    boolean supports(NotificationChannel channel);

    /**
     * Hands the notification to the channel.
     *
     * @throws NotificationDeliveryException when the channel refuses it; the caller records the
     *     failure and still publishes {@code notification.sent}, so the saga always terminates
     */
    void send(Notification notification);
}
