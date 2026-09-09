package com.commerceflow.authservice.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.authservice.dto.AuthResponse;
import com.commerceflow.authservice.dto.GuestSessionRequest;
import com.commerceflow.authservice.entity.Role;
import com.commerceflow.authservice.entity.User;
import com.commerceflow.authservice.repository.UserRepository;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Checking out without creating an account.
 *
 * <h2>A guest still gets an account. They just never chose one.</h2>
 *
 * <p>The alternative — a nullable {@code userId} on orders, a separate guest identity, a
 * parallel way to look up an order — means every downstream feature grows a second code path.
 * The basket, the address book, the order history, the payment, the saga: each would need to know
 * that a customer might not be a customer.
 *
 * <p>So a guest checkout creates a real account with no password, and everything downstream is
 * unchanged. It also gives the customer something worth having: if they later want an account,
 * they set a password through the ordinary reset flow and their order history is already there.
 *
 * <h2>Why an existing address is refused rather than reused</h2>
 *
 * <p>This endpoint hands out a token in exchange for an email address and nothing else. If it
 * returned a token for an address that already has an account, anyone could type your email and
 * receive a session as you.
 *
 * <p>So an address with an account gets a refusal that says to sign in — even though that is
 * mildly annoying for a returning customer who wanted to check out quickly. The alternative is not
 * a login system at all.
 *
 * <h2>What is knowingly accepted</h2>
 *
 * <p>Somebody can guest-check-out using an address that is not theirs. The confirmation email goes
 * to the real owner, and if that owner later registers, the stranger's order is in their history —
 * along with the address it was sent to.
 *
 * <p>That is the same exposure as any mistyped email at any shop, it requires the attacker to pay
 * for the order, and the only way to close it is to verify the address before taking payment,
 * which is exactly the friction guest checkout exists to remove. Stated here rather than
 * discovered later.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestSessionService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;

    /**
     * Opens a session for somebody who does not want an account.
     *
     * @throws ConflictException when the address already has one. The message says to sign in;
     *     see the class comment for why this is not simply reused.
     */
    @Transactional
    public AuthResponse start(GuestSessionRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        if (users.existsByEmailIgnoreCase(email)) {
            throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED,
                    "That address already has an account. Please sign in to check out.");
        }

        Instant now = Instant.now();
        User guest = users.save(User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .fullName(displayName(request.fullName(), email))
                // A random hash nobody knows, rather than an empty or null one. Password
                // comparison then simply fails, instead of relying on every future code path
                // remembering to check the guest flag before comparing.
                .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                .enabled(true)
                .emailVerified(false)
                .guest(true)
                .roles(EnumSet.of(Role.CUSTOMER))
                .createdAt(now)
                .updatedAt(now)
                .build());

        log.info("Opened a guest session for {}", guest.getId());
        return authService.issueTokens(guest);
    }

    /**
     * A name to put on the order.
     *
     * <p>Falls back to the local part of the address rather than to "Guest": a parcel addressed to
     * Guest is a parcel a courier cannot deliver, and the recipient name on a shipping label comes
     * from the checkout form anyway.
     */
    private static String displayName(String supplied, String email) {
        if (supplied != null && !supplied.isBlank()) {
            String trimmed = supplied.trim();
            return trimmed.length() > 150 ? trimmed.substring(0, 150) : trimmed;
        }
        String local = email.split("@")[0];
        return local.isBlank() ? "Customer" : local;
    }
}
