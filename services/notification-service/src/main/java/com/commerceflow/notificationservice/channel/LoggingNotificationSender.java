package com.commerceflow.notificationservice.channel;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.commerceflow.notificationservice.entity.Notification;
import com.commerceflow.notificationservice.entity.NotificationChannel;

import lombok.extern.slf4j.Slf4j;

/**
 * Default channel: writes the notification to the structured log.
 *
 * <p>Deliberately the fallback for every channel, so the platform runs end to end with no
 * third-party credentials and no outbound network access. A real provider adapter declares a
 * higher precedence and takes over for the channels it supports.
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class LoggingNotificationSender implements NotificationSender {

    @Override
    public boolean supports(NotificationChannel channel) {
        return true;
    }

    @Override
    public void send(Notification notification) {
        log.info("NOTIFICATION [{}] to {} | subject: {} | reference: {} | body: {}",
                notification.getChannel(),
                notification.getRecipient(),
                notification.getSubject(),
                notification.getReferenceId(),
                notification.getContent().replace("\n", " "));
    }
}
