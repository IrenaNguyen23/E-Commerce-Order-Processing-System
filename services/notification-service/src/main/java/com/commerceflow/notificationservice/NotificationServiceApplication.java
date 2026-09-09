package com.commerceflow.notificationservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Notification Service — the terminal participant of the saga.
 *
 * <p>Tells the customer how their order ended, and publishes {@code notification.sent} so the
 * outcome is auditable end to end.
 */
/*
 * @EnableScheduling is declared here rather than inherited from the outbox auto-configuration.
 * The retention sweep must keep running even if the outbox relay is ever switched off, and a
 * housekeeping job that stops silently is worse than one that was never there.
 */
@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
