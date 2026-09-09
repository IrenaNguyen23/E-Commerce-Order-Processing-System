package com.commerceflow.common.event;

import java.util.Map;
import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Published on {@code notification.send} — a generic "please deliver this message" command.
 *
 * <p>Used by services that need a notification outside the order saga, e.g. Auth Service
 * sending a welcome mail after registration.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class NotificationSendEvent extends DomainEvent {

    private static final long serialVersionUID = 1L;

    private UUID userId;

    /** Email address or phone number, depending on {@link #channel}. */
    private String recipient;

    /** {@code EMAIL}, {@code SMS} or {@code PUSH}. */
    private String channel;

    /** Template identifier resolved by Notification Service. */
    private String templateCode;

    private String subject;

    /** Template placeholders. */
    private Map<String, String> params;

    /** Business entity this notification refers to, typically an order id. */
    private UUID referenceId;

    @Override
    public String partitionKey() {
        if (referenceId != null) {
            return referenceId.toString();
        }
        return userId != null ? userId.toString() : null;
    }
}
