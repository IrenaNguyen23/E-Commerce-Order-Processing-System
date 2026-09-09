package com.commerceflow.common.event;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Envelope carried by every message on the saga topics.
 *
 * <p>Field names match {@code .github/docs/contracts/events.md}; additional metadata
 * ({@code eventType}, {@code correlationId}) is additive and backwards compatible.
 */
@Getter
@Setter
@ToString
@SuperBuilder
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class DomainEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Globally unique id of this event; the idempotency key for every consumer. */
    private UUID eventId;

    /** Logical event name, see {@link com.commerceflow.common.constant.EventTypes}. */
    private String eventType;

    /** Instant the fact happened, UTC. */
    private Instant timestamp;

    /** Propagated end-to-end so a whole saga can be traced in the logs. */
    private String correlationId;

    /**
     * Identifies the saga instance this message belongs to.
     *
     * <p>Stamped by the orchestrator on every command and echoed unchanged by the participant on
     * its reply. It is what lets the orchestrator find the right saga row without inferring it
     * from business fields, and what makes a reply for an unknown saga safe to drop.
     *
     * <p>Null on messages outside the order saga, e.g. the welcome mail Auth Service sends.
     */
    private UUID sagaId;

    /**
     * The {@code eventId} of the message that caused this one.
     *
     * <p>On a reply it is the id of the command being answered, which links the two halves of a
     * step in the audit trail and lets a late reply to a superseded command be recognised.
     */
    private UUID causationId;

    /**
     * Fills in the envelope defaults for a freshly created event.
     *
     * @param type          logical event type
     * @param correlationId correlation id of the originating request, may be {@code null}
     * @return this instance, for chaining
     */
    public DomainEvent initEnvelope(String type, String correlationId) {
        if (this.eventId == null) {
            this.eventId = UUID.randomUUID();
        }
        if (this.timestamp == null) {
            this.timestamp = Instant.now();
        }
        this.eventType = type;
        this.correlationId = correlationId;
        return this;
    }

    /**
     * Marks this message as a reply to {@code command}, copying the saga linkage across.
     *
     * <p>Every participant calls this on the way out, so the orchestrator never has to guess
     * which command a reply belongs to.
     *
     * @param command the command being answered
     * @return this instance, for chaining
     */
    public DomainEvent replyTo(DomainEvent command) {
        this.sagaId = command.getSagaId();
        this.causationId = command.getEventId();
        return this;
    }

    /** Business key used as the Kafka partition key so one order is processed in order. */
    public abstract String partitionKey();
}
