package com.commerceflow.common.event;

import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Base class for every instruction the orchestrator sends to a participant.
 *
 * <p>A command differs from an event in three ways that matter operationally: it has exactly one
 * intended recipient, it may be re-sent when a reply does not arrive, and the recipient is
 * obliged to answer. The last point is the contract that keeps an orchestrated saga from
 * stalling — see the reply rule on each participant.
 *
 * <p>Commands are keyed by order id so every message about one order, in either direction, lands
 * on the same partition and is processed in the order it was sent.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public abstract class SagaCommand extends DomainEvent {

    private static final long serialVersionUID = 1L;

    /** The order this command is about. Always equal to {@code sagaId} in the order saga. */
    private UUID orderId;

    /** Human readable order reference, carried so participants can log something meaningful. */
    private String orderNumber;

    /**
     * How many times this command has been sent, starting at 1.
     *
     * <p>Present so a participant can tell a first delivery from an orchestrator re-send in its
     * logs. It must never change how the participant behaves: a re-send has to produce the same
     * reply as the original, which is exactly what makes re-sending safe.
     */
    @lombok.Builder.Default
    private int attempt = 1;

    @Override
    public String partitionKey() {
        if (orderId != null) {
            return orderId.toString();
        }
        return getSagaId() != null ? getSagaId().toString() : null;
    }
}
