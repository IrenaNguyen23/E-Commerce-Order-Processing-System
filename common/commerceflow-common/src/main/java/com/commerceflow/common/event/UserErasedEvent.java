package com.commerceflow.common.event;

import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Published on {@code user.erased} — a customer has asked to be forgotten.
 *
 * <h2>An announcement, not a command</h2>
 *
 * <p>Deliberately a domain event rather than a saga. Erasure is not a transaction that has to
 * succeed or unwind as a whole: each service scrubs what it holds, independently, and a service
 * that is down when the event is published catches up when it comes back. GDPR gives a month;
 * eventual consistency measured in seconds is not the problem it would be for a payment.
 *
 * <p>It also means the list of services that hold personal data is not written down anywhere as a
 * saga definition that somebody has to remember to extend. A new service subscribes and scrubs its
 * own copy — which is the only arrangement where "did we get everything" has a chance of staying
 * true.
 *
 * <h2>What each subscriber is expected to do</h2>
 *
 * <p><b>Anonymise, not delete.</b> An order is a financial record and has to survive; what must not
 * survive is the customer's name, address and email inside it. Deleting the row would take the
 * money with it, and an accountant would find a hole where a sale used to be.
 *
 * <p>Data that is <em>only</em> personal — a basket, a wishlist, a saved address — is deleted
 * outright, because nothing else depends on it.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class UserErasedEvent extends DomainEvent {

    private static final long serialVersionUID = 1L;

    /** The account being erased. Subscribers scrub everything they hold under this id. */
    private UUID userId;

    /**
     * What to write where a name is required.
     *
     * <p>Carried on the event rather than each service inventing its own, so an operator scanning
     * five databases sees the same placeholder everywhere rather than guessing whether three
     * different strings mean the same thing.
     */
    private String placeholderName;

    /**
     * A non-routable address to write where one is required.
     *
     * <p>The {@code .invalid} top-level domain is reserved by RFC 2606 precisely for this: it can
     * never be registered, so an anonymised row can never accidentally become a real person's
     * address if something later tries to send mail to it.
     */
    private String placeholderEmail;

    @Override
    public String partitionKey() {
        return userId != null ? userId.toString() : null;
    }
}
