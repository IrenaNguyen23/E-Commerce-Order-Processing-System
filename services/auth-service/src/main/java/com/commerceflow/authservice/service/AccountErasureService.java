package com.commerceflow.authservice.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.authservice.entity.Role;
import com.commerceflow.authservice.entity.User;
import com.commerceflow.authservice.repository.RefreshTokenRepository;
import com.commerceflow.authservice.repository.UserAddressRepository;
import com.commerceflow.authservice.repository.UserRepository;
import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.event.UserErasedEvent;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.common.outbox.OutboxService;
import com.commerceflow.common.security.AuthenticatedUser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Forgetting a customer.
 *
 * <h2>Anonymise, do not delete</h2>
 *
 * <p>An account cannot simply be removed, because orders reference it and orders are financial
 * records that have to survive. Deleting the row would take the money with it, and an accountant
 * would find a hole where a sale used to be.
 *
 * <p>So the row stays and everything identifying about it is overwritten: the address becomes a
 * non-routable placeholder, the name becomes a placeholder, the phone goes, the password hash is
 * replaced with a value nobody knows, and every session is revoked. What remains is an account
 * that can never be signed into and identifies nobody.
 *
 * <h2>The other services are told, not asked</h2>
 *
 * <p>{@code user.erased} goes out on the outbox in the same transaction. Each subscriber scrubs
 * what it holds — the order snapshot, the review author, the basket — on its own schedule. One
 * that is down catches up when it returns.
 *
 * <p>A saga would be the wrong shape here. Erasure has a legal deadline measured in weeks, not the
 * seconds a payment does, and there is nothing to unwind: a service that has scrubbed its copy
 * does not un-scrub it because another service failed.
 *
 * <h2>What is deliberately not offered</h2>
 *
 * <p>There is no undo. Anonymisation destroys the information needed to reverse it, which is the
 * point — an erasure that can be undone by an administrator is not an erasure. A customer who
 * changes their mind registers again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountErasureService {

    private static final String AGGREGATE_TYPE = "USER";

    /**
     * RFC 2606 reserves {@code .invalid} so it can never be registered.
     *
     * <p>Which matters: an anonymised address must never become a real person's, and a made-up
     * domain like {@code deleted.local} could be bought by somebody tomorrow.
     */
    private static final String ERASED_DOMAIN = "@erased.invalid";

    private static final String ERASED_NAME = "Deleted account";

    private final UserRepository users;
    private final UserAddressRepository addresses;
    private final RefreshTokenRepository refreshTokens;
    private final OutboxService outboxService;
    private final AuditService auditService;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    /**
     * Erases an account.
     *
     * <p>A customer may erase their own; an administrator may erase anyone's. Somebody else's
     * returns not-found rather than forbidden, for the same reason as everywhere else.
     *
     * @throws ConflictException when the account is the last enabled administrator — erasing it
     *     would lock everybody out of the back office, and unlike most mistakes this one cannot be
     *     undone by signing in and putting it back
     */
    @Transactional
    public void erase(UUID userId, AuthenticatedUser caller) {
        User user = users.findById(userId)
                .filter(candidate -> caller.isAdmin() || candidate.getId().equals(caller.userId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Account not found: " + userId));

        if (isErased(user)) {
            // Not an error. A retried request, or a customer who asked twice, should get the same
            // answer as the first time rather than a failure about something already done.
            log.info("Account {} is already erased", userId);
            return;
        }

        requireNotTheLastAdministrator(user);

        String placeholderEmail = "erased-" + UUID.randomUUID() + ERASED_DOMAIN;

        // Audited before the identity goes, so the entry can still say whose account it was.
        // Recording it afterwards would produce a line naming a placeholder.
        auditService.record(caller, "ACCOUNT_ERASED", "USER", userId,
                caller.userId().equals(userId)
                        ? "Customer erased their own account"
                        : "Erased the account of " + user.getEmail());

        user.setEmail(placeholderEmail);
        user.setFullName(ERASED_NAME);
        user.setPhone(null);
        user.setEmailVerified(false);
        user.setEnabled(false);
        // A hash nobody knows rather than null: password comparison then simply fails, instead of
        // depending on every future code path remembering to check whether an account is erased.
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setUpdatedAt(Instant.now());
        users.save(user);

        // Only personal, and nothing references them — so these go rather than being blanked.
        addresses.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId).forEach(addresses::delete);
        refreshTokens.revokeAllForUser(userId, Instant.now());

        outboxService.append(AGGREGATE_TYPE, userId.toString(), UserErasedEvent.builder()
                .userId(userId)
                .placeholderName(ERASED_NAME)
                .placeholderEmail(placeholderEmail)
                .build());

        log.warn("Erased account {}. Orders are kept and anonymised by their own service.", userId);
    }

    /** Whether this account has already been through erasure. */
    public static boolean isErased(User user) {
        return user.getEmail() != null && user.getEmail().endsWith(ERASED_DOMAIN);
    }

    /**
     * Refuses to erase the last administrator anybody could still sign in as.
     *
     * <p>Counting <em>enabled</em> administrators, because a disabled one cannot let anyone back
     * in. The same rule guards role changes; it matters more here because erasure cannot be
     * reversed by an operator who realises what they have done.
     */
    private void requireNotTheLastAdministrator(User user) {
        if (!user.getRoles().contains(Role.ADMIN) || !user.isEnabled()) {
            return;
        }
        if (users.countEnabledWithRole(Role.ADMIN) <= 1) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "This is the last administrator who can still sign in. Erasing it would lock "
                            + "everybody out of the back office, and unlike most mistakes this one "
                            + "cannot be undone. Promote somebody else first.");
        }
    }

    /** Guard for callers that reach this service without a principal. */
    public static AuthenticatedUser require(AuthenticatedUser caller) {
        if (caller == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return caller;
    }
}
