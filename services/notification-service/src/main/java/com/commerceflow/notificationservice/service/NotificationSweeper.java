package com.commerceflow.notificationservice.service;

import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.notificationservice.config.NotificationProperties;
import com.commerceflow.notificationservice.entity.NotificationStatus;
import com.commerceflow.notificationservice.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Removes delivered notifications past their retention.
 *
 * <h2>Only the ones that worked</h2>
 *
 * <p>{@code SENT} only. A {@code FAILED} notification is a customer who was never told something —
 * an order confirmation that did not arrive, a refund nobody mentioned — and it is kept until
 * somebody has dealt with it. A {@code PENDING} one has not been tried yet.
 *
 * <p>That asymmetry is the point of the job. Sweeping by age alone would quietly delete the
 * evidence of every message the platform failed to deliver, which is precisely the set worth
 * keeping.
 *
 * <h2>What the row is, and is not</h2>
 *
 * <p>It is a delivery record: this address was told this thing at this time. It is not the message
 * — the customer has that in their inbox — and it is not the order, which lives in Order Service
 * and is kept indefinitely.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationSweeper {

    private final NotificationRepository notifications;
    private final NotificationProperties properties;

    @Scheduled(cron = "${commerceflow.notification.sweep-cron:0 45 4 * * *}")
    @Transactional
    public void sweep() {
        Instant cutoff = Instant.now().minus(properties.getRetention());
        int removed = notifications.deleteByStatusInAndCreatedAtBefore(
                List.of(NotificationStatus.SENT), cutoff);

        if (removed > 0) {
            log.info("Removed {} delivered notification(s) older than {}. Failed and pending "
                    + "notifications are never swept.", removed, cutoff);
        }
    }
}
