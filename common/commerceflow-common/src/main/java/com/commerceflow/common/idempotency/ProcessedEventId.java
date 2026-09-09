package com.commerceflow.common.idempotency;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Composite key of the idempotency ledger.
 *
 * <p>Scoped by consumer group so several listeners inside the same service can each process the
 * same event exactly once.
 */
@Embeddable
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ProcessedEventId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "consumer_group", nullable = false, length = 128)
    private String consumerGroup;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;
}
