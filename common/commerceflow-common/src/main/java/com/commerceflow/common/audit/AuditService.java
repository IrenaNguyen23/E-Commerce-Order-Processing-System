package com.commerceflow.common.audit;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.context.CorrelationContext;
import com.commerceflow.common.security.AuthenticatedUser;

/**
 * Records what an operator did.
 *
 * <h2>Written in the caller's transaction</h2>
 *
 * <p>{@link Propagation#REQUIRED}, so the entry commits with the change it describes or not at
 * all. An audit trail written in its own transaction records things that were rolled back and
 * misses things that were not — and both failures point the wrong way during an investigation.
 *
 * <h2>It must not be the reason a request fails</h2>
 *
 * <p>That is in tension with the above, and the tension is resolved deliberately: the write is in
 * the transaction, but every <em>avoidable</em> reason it could throw is removed first. The
 * summary is truncated rather than rejected, a missing actor is allowed, and nothing here
 * validates. What remains — the database being gone — would have failed the business change
 * anyway.
 *
 * <p>The alternative, catching and swallowing, produces the worst outcome available: a change that
 * happened with no record that it did, and nobody knowing.
 *
 * <h2>Who did it comes from the security context, not from a parameter</h2>
 *
 * <p>{@link #record(String, String, Object, String)} reads the current principal itself. The
 * alternative — threading an {@code AuthenticatedUser} through every service method that might one
 * day be audited — is real churn for no gain in correctness: the actor is a property of the
 * request, not of the operation's arguments, and the filter has already put it where Spring keeps
 * it.
 *
 * <p>It also has a practical effect worth more than the purity: adding an audit line to a new
 * endpoint is one statement rather than a signature change and a dozen call sites, which is the
 * difference between auditing being done and being meant to be done.
 *
 * <p>The explicit overload stays for callers that already hold the principal, and for tests.
 */
public class AuditService {

    /**
     * The database column is 500. Truncating at 497 leaves room for the ellipsis.
     *
     * <p>Truncated rather than refused because an over-long summary is a caller being generous,
     * not a caller being wrong, and refusing would fail the operation being audited.
     */
    private static final int MAX_SUMMARY = 497;

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    /**
     * Records an action taken by whoever is making the current request.
     *
     * <p>Resolves the actor from the security context. Falls back to no actor when there is no
     * authenticated principal, which happens for a scheduled job — and reads correctly as "the
     * system did this" rather than being attributed to nobody in particular.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String action, String targetType, Object targetId, String summary) {
        record(currentActor(), action, targetType, targetId, summary);
    }

    /**
     * Records an action taken by a named person.
     *
     * @param actor who did it; may be {@code null} for something the system did on its own
     * @param action what was done, e.g. {@code ORDER_CANCELLED}
     * @param targetType what kind of thing it was done to, e.g. {@code ORDER}
     * @param targetId that thing's id
     * @param summary one sentence naming the change. <b>Do not put the old and new values in
     *     here</b> — see {@link AuditEntry} for why that turns the audit table into a copy of
     *     personal data with a two-year retention
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(AuthenticatedUser actor, String action, String targetType,
            Object targetId, String summary) {

        AuditEntry entry = AuditEntry.builder()
                .id(UUID.randomUUID())
                .actorId(actor == null ? null : actor.userId())
                // Copied, not joined. The account may be renamed or later erased, and this line
                // still has to read as a sentence about a person.
                .actorEmail(actor == null ? null : actor.email())
                .action(action)
                .targetType(targetType)
                .targetId(targetId == null ? null : String.valueOf(targetId))
                .summary(trim(summary))
                .correlationId(CorrelationContext.get())
                .occurredAt(Instant.now())
                .build();

        repository.save(entry);

        // Also to the log, at info. The table is the record of account; this is what somebody
        // tailing logs during an incident actually sees, and the correlation id ties the two.
        log.info("AUDIT {} {} {} by {}", action, targetType, entry.getTargetId(),
                entry.getActorEmail() == null ? "the system" : entry.getActorEmail());
    }

    /** For a scheduled job or a message handler, where there is nobody to attribute it to. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordSystem(String action, String targetType, Object targetId, String summary) {
        record((AuthenticatedUser) null, action, targetType, targetId, summary);
    }

    /**
     * The principal on the current request, or {@code null}.
     *
     * <p>Deliberately tolerant. A missing or unexpected principal produces an unattributed entry
     * rather than an exception: an audit trail is not worth failing a request over, and an entry
     * that says "the system" is more use than no entry at all.
     */
    private static AuthenticatedUser currentActor() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getPrincipal() instanceof AuthenticatedUser user ? user : null;
    }

    private static String trim(String summary) {
        if (summary == null) {
            return null;
        }
        return summary.length() <= MAX_SUMMARY + 3
                ? summary
                : summary.substring(0, MAX_SUMMARY) + "...";
    }
}
