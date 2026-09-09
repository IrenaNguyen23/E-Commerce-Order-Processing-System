package com.commerceflow.authservice.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.authservice.config.AuthProperties;
import com.commerceflow.authservice.entity.TokenPurpose;
import com.commerceflow.authservice.entity.User;
import com.commerceflow.authservice.entity.VerificationToken;
import com.commerceflow.authservice.repository.RefreshTokenRepository;
import com.commerceflow.authservice.repository.UserRepository;
import com.commerceflow.authservice.repository.VerificationTokenRepository;
import com.commerceflow.common.event.NotificationSendEvent;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.outbox.OutboxService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Getting back into an account, and proving an address is yours.
 *
 * <p>Two flows, one mechanism: mint a high-entropy token, email it, accept it back once inside a
 * window. Kept out of {@code AuthService} because that class is about credentials that are
 * presented, and this one is about grants that are sent.
 *
 * <h2>Never say whether an address is registered</h2>
 *
 * <p>{@link #requestPasswordReset} and {@link #resendVerification} return the same way for an
 * address that exists, one that does not, and one belonging to a disabled account. That is
 * deliberate: an endpoint that answers differently is a free membership oracle, and "is this
 * person a customer of yours" is not a question a stranger gets to ask. The caller cannot
 * distinguish the cases, so neither can an attacker enumerating a leaked address list.
 *
 * <p>The cost is a worse error message for a customer who mistypes their address: they are told
 * to check their inbox and nothing arrives. That is the right trade, and it is why the response
 * says <em>if an account exists</em> rather than <em>we have sent you an email</em>.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountRecoveryService {

    private static final String AGGREGATE_TYPE = "USER";

    /** 32 bytes of entropy. Guessing one is not a threat model anybody needs to worry about. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final VerificationTokenRepository tokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final OutboxService outboxService;
    private final AuthProperties properties;

    // =====================================================================================
    // Password reset
    // =====================================================================================

    /**
     * Starts a password reset, if there is an account to start one for.
     *
     * <p>Returns identically either way — see the class note on enumeration.
     */
    @Transactional
    public void requestPasswordReset(String email) {
        Optional<User> found = userRepository.findByEmailIgnoreCase(normalise(email));

        if (found.isEmpty() || !found.get().isEnabled()) {
            // Logged, not answered. An operator investigating a support ticket needs to know the
            // request arrived; the caller does not get to learn why nothing happened.
            log.info("Password reset requested for an address with no usable account");
            return;
        }

        User user = found.get();
        String token = issue(user, TokenPurpose.PASSWORD_RESET);

        Map<String, String> params = new HashMap<>();
        params.put("fullName", user.getFullName());
        params.put("resetUrl", link("/reset-password", token));
        params.put("expiresInMinutes",
                String.valueOf(TokenPurpose.PASSWORD_RESET.lifetime().toMinutes()));

        notify(user, "PASSWORD_RESET", "Reset your CommerceFlow password", params);
        log.info("Password reset token issued for user {}", user.getId());
    }

    /**
     * Completes a password reset.
     *
     * <p>Three things happen together, and all three matter:
     *
     * <ol>
     *   <li>The password changes.
     *   <li><b>Every refresh token is revoked.</b> A reset is what someone does when they think
     *       their account is compromised, and leaving the attacker's session alive would make the
     *       whole exercise pointless.
     *   <li>The address is marked verified. Redeeming the token is proof the mailbox was reachable
     *       — the same proof the verification flow asks for, so asking again would be theatre.
     * </ol>
     *
     * @throws BusinessException when the token is unknown, already used, or expired
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        VerificationToken grant = redeem(token, TokenPurpose.PASSWORD_RESET);

        User user = userRepository.findById(grant.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID,
                        "This reset link is no longer valid"));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setEmailVerified(true);
        user.setUpdatedAt(Instant.now());

        int revoked = refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now());

        notify(user, "PASSWORD_CHANGED", "Your CommerceFlow password was changed",
                Map.of("fullName", user.getFullName()));

        log.warn("Password reset completed for user {}; {} session(s) revoked", user.getId(),
                revoked);
    }

    // =====================================================================================
    // Email verification
    // =====================================================================================

    /**
     * Issues a verification link and sends it. Called on registration and on request.
     *
     * <p>Runs inside the caller's transaction so the token, the outbox row and whatever else the
     * caller is writing commit together — an account that exists with no way to verify it would
     * need a support ticket to fix.
     */
    public void sendVerificationLink(User user) {
        String token = issue(user, TokenPurpose.EMAIL_VERIFICATION);

        Map<String, String> params = new HashMap<>();
        params.put("fullName", user.getFullName());
        params.put("verificationUrl", link("/verify-email", token));

        notify(user, "EMAIL_VERIFICATION", "Confirm your email address", params);
    }

    /**
     * Marks an address verified, and welcomes the customer properly.
     *
     * <p>The welcome message waits until here rather than going out at registration, so it only
     * ever reaches an address that actually exists.
     *
     * @throws BusinessException when the token is unknown, already used, or expired
     */
    @Transactional
    public void verifyEmail(String token) {
        VerificationToken grant = redeem(token, TokenPurpose.EMAIL_VERIFICATION);

        User user = userRepository.findById(grant.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID,
                        "This verification link is no longer valid"));

        if (user.isEmailVerified()) {
            // Someone clicked the link twice. Nothing to do, and nothing worth complaining about.
            log.debug("Address for user {} was already verified", user.getId());
            return;
        }

        user.setEmailVerified(true);
        user.setUpdatedAt(Instant.now());

        notify(user, "USER_WELCOME", "Welcome to CommerceFlow",
                Map.of("fullName", user.getFullName()));

        log.info("Address verified for user {}", user.getId());
    }

    /** Sends another verification link, if there is an unverified account to send one for. */
    @Transactional
    public void resendVerification(String email) {
        Optional<User> found = userRepository.findByEmailIgnoreCase(normalise(email));

        if (found.isEmpty() || !found.get().isEnabled() || found.get().isEmailVerified()) {
            log.info("Verification resend requested for an address that does not need one");
            return;
        }
        sendVerificationLink(found.get());
    }

    // =====================================================================================
    // Internals
    // =====================================================================================

    /**
     * Mints a token, stores its digest, and returns the value exactly once.
     *
     * <p>Any outstanding token for the same purpose is burned first. Otherwise every time a
     * customer clicks "send it again" they leave another working key to their account sitting in
     * their inbox, and the oldest one lives longest.
     */
    private String issue(User user, TokenPurpose purpose) {
        tokenRepository.consumeOutstanding(user.getId(), purpose, Instant.now());

        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        tokenRepository.save(VerificationToken.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .purpose(purpose)
                .tokenHash(AuthService.hash(token))
                .expiresAt(Instant.now().plus(purpose.lifetime()))
                .createdAt(Instant.now())
                .build());

        return token;
    }

    /**
     * Looks a token up and burns it.
     *
     * <p>Unknown, expired and already-used all raise the same error with the same message. The
     * difference is only useful to someone probing, and a customer whose link has expired needs
     * the same next step either way: ask for a new one.
     */
    private VerificationToken redeem(String token, TokenPurpose purpose) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "This link is no longer valid");
        }

        VerificationToken grant = tokenRepository
                .findByTokenHashAndPurpose(AuthService.hash(token), purpose)
                .filter(VerificationToken::isRedeemable)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID,
                        "This link is no longer valid. Please request a new one."));

        grant.consume();
        return grant;
    }

    /** Queues a message in the same transaction as the change it is telling the customer about. */
    private void notify(User user, String templateCode, String subject, Map<String, String> params) {
        outboxService.append(AGGREGATE_TYPE, user.getId().toString(),
                NotificationSendEvent.builder()
                        .userId(user.getId())
                        .recipient(user.getEmail())
                        .channel("EMAIL")
                        .templateCode(templateCode)
                        .subject(subject)
                        .params(params)
                        .referenceId(user.getId())
                        .build());
    }

    /**
     * Builds a link into the web application.
     *
     * <p>Points at the storefront, not the gateway: the customer needs a page that can take a new
     * password, and those are two different hosts the moment the frontend is deployed anywhere of
     * its own.
     */
    private String link(String path, String token) {
        String base = properties.getWebBaseUrl();
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + path + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
