package com.commerceflow.notificationservice.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.commerceflow.common.constant.KafkaTopics;
import com.commerceflow.common.event.NotificationSendEvent;
import com.commerceflow.common.kafka.SagaConsumerGroups;
import com.commerceflow.notificationservice.service.NotificationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The only saga input Notification Service has.
 *
 * <p>One command topic, whoever the sender is. The orchestrator uses it for the last step of the
 * order saga; Auth Service uses it for the welcome mail. Notification does not need to tell them
 * apart — it renders and delivers, and the reply carries whatever saga linkage arrived with the
 * command, which is what lets the orchestrator recognise its own.
 *
 * <p>The two order-event listeners this class used to have are gone. Deciding that a completed
 * order deserves an email was a policy decision living in the wrong service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCommandListener {

    private final NotificationService notificationService;

    @KafkaListener(
            topics = KafkaTopics.NOTIFICATION_SEND,
            groupId = SagaConsumerGroups.NOTIFICATION_SERVICE,
            id = "notification-commands")
    public void onNotificationRequested(@Payload NotificationSendEvent command) {
        log.debug("Command {} for recipient {} (saga {})", command.getEventType(),
                command.getRecipient(), command.getSagaId());
        notificationService.onNotificationRequested(command);
    }
}
