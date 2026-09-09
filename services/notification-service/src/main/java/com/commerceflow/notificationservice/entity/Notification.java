package com.commerceflow.notificationservice.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One notification, from the moment it is queued to the moment a channel accepts it. */
@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notifications_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_notifications_reference", columnList = "reference_id"),
        @Index(name = "idx_notifications_status", columnList = "status")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    /** Business entity this notification is about, typically an order id. */
    @Column(name = "reference_id")
    private UUID referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private NotificationChannel channel;

    @Column(name = "recipient", nullable = false, length = 255)
    private String recipient;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NotificationStatus status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void markSent() {
        this.status = NotificationStatus.SENT;
        this.sentAt = Instant.now();
        this.updatedAt = this.sentAt;
        this.failureReason = null;
    }

    public void markFailed(String reason) {
        this.status = NotificationStatus.FAILED;
        this.failureReason = truncate(reason);
        this.retryCount = this.retryCount + 1;
        this.updatedAt = Instant.now();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
