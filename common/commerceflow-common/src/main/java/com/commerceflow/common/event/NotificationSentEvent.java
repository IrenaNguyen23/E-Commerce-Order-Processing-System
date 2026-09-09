package com.commerceflow.common.event;

import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Published on {@code notification.sent} — the terminal event of the saga, emitted once a
 * notification has actually been dispatched (or permanently failed).
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class NotificationSentEvent extends DomainEvent {

    private static final long serialVersionUID = 1L;

    private UUID notificationId;
    private UUID userId;
    private UUID referenceId;
    private String channel;
    private String recipient;
    private String templateCode;

    /** {@code SENT} or {@code FAILED}. */
    private String status;

    private String failureReason;

    @Override
    public String partitionKey() {
        if (referenceId != null) {
            return referenceId.toString();
        }
        return notificationId != null ? notificationId.toString() : null;
    }
}
