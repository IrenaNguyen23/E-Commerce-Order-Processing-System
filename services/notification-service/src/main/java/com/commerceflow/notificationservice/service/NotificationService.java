package com.commerceflow.notificationservice.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.dto.PageResponse;
import com.commerceflow.common.event.NotificationSendEvent;
import com.commerceflow.common.event.NotificationSentEvent;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.common.idempotency.IdempotencyService;
import com.commerceflow.common.kafka.SagaConsumerGroups;
import com.commerceflow.common.outbox.OutboxService;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.notificationservice.channel.NotificationDeliveryException;
import com.commerceflow.notificationservice.channel.NotificationSender;
import com.commerceflow.notificationservice.config.NotificationProperties;
import com.commerceflow.notificationservice.dto.NotificationResponse;
import com.commerceflow.notificationservice.entity.Notification;
import com.commerceflow.notificationservice.entity.NotificationChannel;
import com.commerceflow.notificationservice.entity.NotificationStatus;
import com.commerceflow.notificationservice.entity.NotificationType;
import com.commerceflow.notificationservice.mapper.NotificationMapper;
import com.commerceflow.notificationservice.repository.NotificationRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Notification as a saga participant.
 *
 * <p>It carries out one command — {@code notification.send} — and answers it with
 * {@code notification.sent}. It no longer subscribes to {@code order.completed} or
 * {@code order.cancelled}: what to tell the customer, and when, is the orchestrator's decision,
 * and this service renders and delivers it.
 *
 * <p>The same command topic still serves everything outside the order saga — the welcome mail
 * Auth Service sends arrives here too. Those messages carry no {@code sagaId}, and the reply
 * echoes that absence, which is how the orchestrator knows to ignore them.
 *
 * <p><b>The reply rule:</b> a channel refusing a message is recorded as a failed notification but
 * still answers the command. An undeliverable email is a delivery problem; leaving a paid order
 * looking unfinished because of one would be worse.
 */
@Slf4j
@Service
public class NotificationService {

    private static final String AGGREGATE_TYPE = "NOTIFICATION";
    private static final String GROUP = SagaConsumerGroups.NOTIFICATION_SERVICE;

    private static final List<String> SORTABLE_FIELDS = List.of("createdAt", "sentAt", "status");

    private final NotificationRepository notificationRepository;
    private final NotificationTemplateRenderer templateRenderer;
    private final List<NotificationSender> senders;
    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;
    private final NotificationMapper notificationMapper;
    private final NotificationProperties properties;

    public NotificationService(NotificationRepository notificationRepository,
                               NotificationTemplateRenderer templateRenderer,
                               List<NotificationSender> senders,
                               IdempotencyService idempotencyService,
                               OutboxService outboxService,
                               NotificationMapper notificationMapper,
                               NotificationProperties properties) {
        this.notificationRepository = notificationRepository;
        this.templateRenderer = templateRenderer;
        this.senders = senders;
        this.idempotencyService = idempotencyService;
        this.outboxService = outboxService;
        this.notificationMapper = notificationMapper;
        this.properties = properties;
    }

    /**
     * Renders, delivers and answers one notification command.
     *
     * <p>Used by the orchestrator for the last step of both saga paths, and by any service that
     * needs to reach a customer outside the order flow.
     */
    @Transactional
    public void onNotificationRequested(NotificationSendEvent command) {
        if (!idempotencyService.claim(GROUP, command.getEventId(), command.getEventType())) {
            // This exact command was handled before; its reply is already in the outbox.
            return;
        }
        NotificationType type = parseType(command.getTemplateCode());
        Map<String, String> params = new HashMap<>();
        if (command.getParams() != null) {
            params.putAll(command.getParams());
        }
        params.putIfAbsent("subject", command.getSubject());

        dispatch(command, type, parseChannel(command.getChannel()), command.getUserId(),
                command.getRecipient(), command.getReferenceId(), params);
    }

    /** @throws ResourceNotFoundException when the notification does not exist or is not the caller's */
    @Transactional(readOnly = true)
    public NotificationResponse getById(UUID notificationId, AuthenticatedUser caller) {
        return notificationRepository.findById(notificationId)
                .filter(notification -> isVisibleTo(notification, caller))
                .map(notificationMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.NOTIFICATION_NOT_FOUND, "Notification not found: " + notificationId));
    }

    /** A customer sees their own notifications; an operator sees every one. */
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> search(AuthenticatedUser caller, String status,
                                                     int page, int size, String sortBy,
                                                     String direction) {
        UUID userFilter = caller.isAdmin() ? null : caller.userId();
        NotificationStatus statusFilter = parseStatus(status);

        Page<Notification> result = notificationRepository.search(userFilter, statusFilter,
                PageRequest.of(page, size, sort(sortBy, direction)));

        List<NotificationResponse> content = result.getContent().stream()
                .map(notificationMapper::toResponse)
                .toList();
        return PageResponse.of(content, page, size, result.getTotalElements());
    }

    /**
     * Renders, persists, delivers and answers one notification.
     *
     * <p>The record and the {@code notification.sent} reply are written in the same transaction
     * as the delivery attempt, so the audit trail always matches what happened.
     */
    private void dispatch(NotificationSendEvent command, NotificationType type,
                          NotificationChannel channel, UUID userId, String recipient,
                          UUID referenceId, Map<String, String> params) {

        if (recipient == null || recipient.isBlank()) {
            // Nothing to deliver and nothing a retry would fix, but the orchestrator is waiting:
            // answer with the failure rather than letting the step time out.
            log.warn("No recipient on {} notification for {}", type, referenceId);
            replyUndeliverable(command, type, channel, userId, referenceId, "NO_RECIPIENT");
            return;
        }

        Instant now = Instant.now();
        Notification notification = Notification.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .referenceId(referenceId)
                .type(type)
                .channel(channel)
                .recipient(recipient)
                .subject(templateRenderer.renderSubject(type, params))
                .content(templateRenderer.renderBody(type, params))
                .status(NotificationStatus.PENDING)
                .retryCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build();

        String failureReason = null;
        try {
            senderFor(channel).send(notification);
            notification.markSent();
        } catch (NotificationDeliveryException ex) {
            notification.markFailed(ex.getMessage());
            failureReason = ex.getMessage();
            log.error("Could not deliver {} notification {} to {}", type, notification.getId(),
                    recipient, ex);
        }

        notificationRepository.save(notification);

        // Sent whether or not delivery succeeded: the saga must always reach its terminal step,
        // and an undeliverable email is not a reason to leave a paid order looking unfinished.
        NotificationSentEvent reply = NotificationSentEvent.builder()
                .notificationId(notification.getId())
                .userId(userId)
                .referenceId(referenceId)
                .channel(channel.name())
                .recipient(recipient)
                .templateCode(type.name())
                .status(notification.getStatus().name())
                .failureReason(failureReason)
                .build();
        reply.replyTo(command);
        outboxService.append(AGGREGATE_TYPE, notification.getId().toString(), reply);

        log.info("Notification {} ({}) to {} is {}", notification.getId(), type, recipient,
                notification.getStatus());
    }

    /**
     * Answers a command that could not produce a notification at all.
     *
     * <p>There is no notification row to point at, so the reply carries the reason instead. The
     * orchestrator treats it like any other answered final step: the saga ends, and the failure
     * is visible in its step log rather than hidden behind a timeout.
     */
    private void replyUndeliverable(NotificationSendEvent command, NotificationType type,
                                    NotificationChannel channel, UUID userId, UUID referenceId,
                                    String reason) {
        NotificationSentEvent reply = NotificationSentEvent.builder()
                .notificationId(UUID.randomUUID())
                .userId(userId)
                .referenceId(referenceId)
                .channel(channel.name())
                .templateCode(type.name())
                .status(NotificationStatus.FAILED.name())
                .failureReason(reason)
                .build();
        reply.replyTo(command);
        outboxService.append(AGGREGATE_TYPE, String.valueOf(referenceId), reply);
    }

    private NotificationSender senderFor(NotificationChannel channel) {
        return senders.stream()
                .filter(sender -> sender.supports(channel))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No sender is configured for channel " + channel));
    }

    private static boolean isVisibleTo(Notification notification, AuthenticatedUser caller) {
        return caller.isAdmin()
                || (notification.getUserId() != null
                    && notification.getUserId().equals(caller.userId()));
    }

    private NotificationChannel parseChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return properties.getDefaultChannel();
        }
        try {
            return NotificationChannel.valueOf(channel.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown channel {}, falling back to {}", channel, properties.getDefaultChannel());
            return properties.getDefaultChannel();
        }
    }

    private static NotificationType parseType(String templateCode) {
        if (templateCode == null || templateCode.isBlank()) {
            return NotificationType.GENERIC;
        }
        try {
            return NotificationType.valueOf(templateCode.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return NotificationType.GENERIC;
        }
    }

    private static NotificationStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return NotificationStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown notification status: " + status);
        }
    }

    /** Turns a machine reason such as {@code INSUFFICIENT_FUNDS} into readable prose. */
    private static String humanise(String reason) {
        if (reason == null || reason.isBlank()) {
            return "an unexpected problem";
        }
        return reason.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
    }

    private static Sort sort(String sortBy, String direction) {
        String field = SORTABLE_FIELDS.contains(sortBy) ? sortBy : "createdAt";
        Sort.Direction dir =
                "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(dir, field);
    }
}
