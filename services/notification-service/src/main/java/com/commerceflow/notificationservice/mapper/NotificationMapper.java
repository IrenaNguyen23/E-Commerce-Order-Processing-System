package com.commerceflow.notificationservice.mapper;

import org.springframework.stereotype.Component;

import com.commerceflow.notificationservice.dto.NotificationResponse;
import com.commerceflow.notificationservice.entity.Notification;

/** Maps the notification entity onto its public projection. */
@Component
public class NotificationMapper {

    public NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getUserId(),
                notification.getReferenceId(),
                notification.getType().name(),
                notification.getChannel().name(),
                notification.getRecipient(),
                notification.getSubject(),
                notification.getContent(),
                notification.getStatus().name(),
                notification.getFailureReason(),
                notification.getRetryCount(),
                notification.getCreatedAt(),
                notification.getSentAt());
    }
}
