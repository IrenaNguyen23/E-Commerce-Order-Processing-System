package com.commerceflow.authservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.commerceflow.authservice.config.AuthProperties;
import com.commerceflow.authservice.entity.Role;
import com.commerceflow.authservice.entity.TokenPurpose;
import com.commerceflow.authservice.entity.User;
import com.commerceflow.authservice.entity.VerificationToken;
import com.commerceflow.authservice.repository.RefreshTokenRepository;
import com.commerceflow.authservice.repository.UserRepository;
import com.commerceflow.authservice.repository.VerificationTokenRepository;
import com.commerceflow.common.event.DomainEvent;
import com.commerceflow.common.event.NotificationSendEvent;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.outbox.OutboxService;

/**
 * Account recovery is the part of an auth system attackers actually go for, so most of these
 * tests are about what the endpoints refuse to reveal and what a successful reset takes away.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountRecoveryServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private VerificationTokenRepository tokenRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private OutboxService outboxService;

    @Captor
    private ArgumentCaptor<DomainEvent> messageCaptor;

    @Captor
    private ArgumentCaptor<VerificationToken> tokenCaptor;

    private AccountRecoveryService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AccountRecoveryService(userRepository, tokenRepository,
                refreshTokenRepository, new BCryptPasswordEncoder(), outboxService,
                new AuthProperties());

        user = User.builder()
                .id(UUID.randomUUID())
                .email("ada@commerceflow.io")
                .passwordHash("$2a$10$originalhashvaluegoeshereXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX")
                .fullName("Ada Lovelace")
                .enabled(true)
                .emailVerified(false)
                .roles(EnumSet.of(Role.CUSTOMER))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(userRepository.findByEmailIgnoreCase("ada@commerceflow.io"))
                .thenReturn(Optional.of(user));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(tokenRepository.save(any(VerificationToken.class)))
                .thenAnswer(call -> call.getArgument(0));
    }

    @Nested
    @DisplayName("not leaking who has an account")
    class NoEnumeration {

        @Test
        @DisplayName("an unknown address is accepted in silence, exactly like a known one")
        void unknownAddressLooksIdentical() {
            when(userRepository.findByEmailIgnoreCase("nobody@example.com"))
                    .thenReturn(Optional.empty());

            service.requestPasswordReset("nobody@example.com");

            // No exception, no token, no email. The caller cannot tell this apart from success,
            // which is the entire point: "is this person a customer" is not a public question.
            verify(tokenRepository, never()).save(any(VerificationToken.class));
            verify(outboxService, never()).append(anyString(), anyString(), any(DomainEvent.class));
        }

        @Test
        @DisplayName("a disabled account is accepted in silence too")
        void disabledAccountLooksIdentical() {
            user.setEnabled(false);

            service.requestPasswordReset("ada@commerceflow.io");

            verify(outboxService, never()).append(anyString(), anyString(), any(DomainEvent.class));
        }

        @Test
        @DisplayName("resending verification to an already-verified address does nothing, quietly")
        void alreadyVerifiedLooksIdentical() {
            user.setEmailVerified(true);

            service.resendVerification("ada@commerceflow.io");

            verify(outboxService, never()).append(anyString(), anyString(), any(DomainEvent.class));
        }
    }

    @Nested
    @DisplayName("password reset")
    class PasswordReset {

        @Test
        @DisplayName("stores only the digest, and emails a link the customer can actually click")
        void issuesAHashedToken() {
            service.requestPasswordReset("ada@commerceflow.io");

            verify(tokenRepository).save(tokenCaptor.capture());
            VerificationToken saved = tokenCaptor.getValue();

            assertThat(saved.getPurpose()).isEqualTo(TokenPurpose.PASSWORD_RESET);
            assertThat(saved.getExpiresAt()).isAfter(Instant.now());
            // 64 hex characters is a SHA-256 digest. Anything shorter would mean the raw token
            // reached the database, and a database dump would then be a set of working keys.
            assertThat(saved.getTokenHash()).hasSize(64).doesNotContain("=");

            NotificationSendEvent mail = capturedMail();
            assertThat(mail.getTemplateCode()).isEqualTo("PASSWORD_RESET");
            assertThat(mail.getParams().get("resetUrl"))
                    .startsWith("http://localhost:3001/reset-password?token=");
            // The link must point at the storefront, not the API: the customer needs a page
            // that can take a new password.
            assertThat(mail.getParams().get("resetUrl")).doesNotContain("8080");
        }

        @Test
        @DisplayName("asking again burns the previous link")
        void reissuingInvalidatesTheOldToken() {
            service.requestPasswordReset("ada@commerceflow.io");

            // Otherwise every "send it again" leaves another working key in the inbox, and the
            // oldest one outlives all of them.
            verify(tokenRepository).consumeOutstanding(eq(user.getId()),
                    eq(TokenPurpose.PASSWORD_RESET), any(Instant.class));
        }

        @Test
        @DisplayName("completing a reset changes the password and signs the account out everywhere")
        void resetRevokesEverySession() {
            String raw = issuedTokenFor(TokenPurpose.PASSWORD_RESET);
            String originalHash = user.getPasswordHash();

            service.resetPassword(raw, "N3w-pass-2026");

            assertThat(user.getPasswordHash()).isNotEqualTo(originalHash);
            // The reason someone resets a password is that they think someone else has it.
            // Leaving the intruder's session alive would make the whole exercise pointless.
            verify(refreshTokenRepository).revokeAllForUser(eq(user.getId()), any(Instant.class));
        }

        @Test
        @DisplayName("a completed reset also proves the address, so it counts as verified")
        void resetVerifiesTheAddress() {
            String raw = issuedTokenFor(TokenPurpose.PASSWORD_RESET);

            service.resetPassword(raw, "N3w-pass-2026");

            // Redeeming the token *is* proof the mailbox was reachable. Asking for that proof
            // again afterwards would be theatre.
            assertThat(user.isEmailVerified()).isTrue();
        }

        @Test
        @DisplayName("the customer is told their password changed, whether or not they asked")
        void resetNotifiesTheCustomer() {
            String raw = issuedTokenFor(TokenPurpose.PASSWORD_RESET);

            service.resetPassword(raw, "N3w-pass-2026");

            // The one signal that reaches someone whose account was taken over by an attacker
            // who then reset the password.
            assertThat(capturedMail().getTemplateCode()).isEqualTo("PASSWORD_CHANGED");
        }

        @Test
        @DisplayName("a token cannot be used twice")
        void tokenIsSingleUse() {
            VerificationToken grant = grant(TokenPurpose.PASSWORD_RESET);
            grant.consume();
            when(tokenRepository.findByTokenHashAndPurpose(anyString(),
                    eq(TokenPurpose.PASSWORD_RESET))).thenReturn(Optional.of(grant));

            assertThatThrownBy(() -> service.resetPassword("anything", "N3w-pass-2026"))
                    .isInstanceOf(BusinessException.class);
            verify(refreshTokenRepository, never())
                    .revokeAllForUser(any(UUID.class), any(Instant.class));
        }

        @Test
        @DisplayName("an expired token is refused")
        void expiredTokenIsRefused() {
            VerificationToken grant = grant(TokenPurpose.PASSWORD_RESET);
            grant.setExpiresAt(Instant.now().minusSeconds(60));
            when(tokenRepository.findByTokenHashAndPurpose(anyString(),
                    eq(TokenPurpose.PASSWORD_RESET))).thenReturn(Optional.of(grant));

            assertThatThrownBy(() -> service.resetPassword("anything", "N3w-pass-2026"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("an unknown token is refused with the same message as an expired one")
        void unknownTokenIsRefused() {
            when(tokenRepository.findByTokenHashAndPurpose(anyString(), any(TokenPurpose.class)))
                    .thenReturn(Optional.empty());

            // Same error either way. The difference is only useful to somebody probing, and a
            // customer's next step is identical: ask for a new link.
            assertThatThrownBy(() -> service.resetPassword("made-up", "N3w-pass-2026"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("no longer valid");
        }

        @Test
        @DisplayName("a reset token cannot be redeemed as a verification token")
        void purposesDoNotCross() {
            when(tokenRepository.findByTokenHashAndPurpose(anyString(),
                    eq(TokenPurpose.EMAIL_VERIFICATION))).thenReturn(Optional.empty());

            // The lookup is scoped by purpose, so a long-lived verification link can never be
            // turned into a password-reset grant.
            assertThatThrownBy(() -> service.verifyEmail("a-reset-token"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("email verification")
    class EmailVerification {

        @Test
        @DisplayName("confirming an address marks it verified and sends the welcome")
        void verifyMarksAndWelcomes() {
            String raw = issuedTokenFor(TokenPurpose.EMAIL_VERIFICATION);

            service.verifyEmail(raw);

            assertThat(user.isEmailVerified()).isTrue();
            // The welcome waits for this moment so it only ever reaches a real mailbox.
            assertThat(capturedMail().getTemplateCode()).isEqualTo("USER_WELCOME");
        }

        @Test
        @DisplayName("clicking the link twice is not an error")
        void verifyingTwiceIsHarmless() {
            user.setEmailVerified(true);
            String raw = issuedTokenFor(TokenPurpose.EMAIL_VERIFICATION);
            // Issuing the link queued a mail of its own; only what happens next is under test.
            org.mockito.Mockito.clearInvocations(outboxService);

            service.verifyEmail(raw);

            assertThat(user.isEmailVerified()).isTrue();
            verify(outboxService, never()).append(anyString(), anyString(), any(DomainEvent.class));
        }

        @Test
        @DisplayName("the verification link lives far longer than a reset link")
        void lifetimesDifferByRisk() {
            // A verification link only proves an address is reachable. A reset link is a key to
            // the account, so it gets a much smaller window in which a leaked inbox matters.
            assertThat(TokenPurpose.EMAIL_VERIFICATION.lifetime())
                    .isGreaterThan(TokenPurpose.PASSWORD_RESET.lifetime());
            assertThat(TokenPurpose.PASSWORD_RESET.lifetime().toMinutes())
                    .isLessThanOrEqualTo(60);
        }
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    /** Runs the real issue path and hands back the raw token, as the email would carry it. */
    private String issuedTokenFor(TokenPurpose purpose) {
        if (purpose == TokenPurpose.PASSWORD_RESET) {
            service.requestPasswordReset("ada@commerceflow.io");
        } else {
            service.sendVerificationLink(user);
        }

        verify(tokenRepository).save(tokenCaptor.capture());
        VerificationToken saved = tokenCaptor.getValue();
        when(tokenRepository.findByTokenHashAndPurpose(saved.getTokenHash(), purpose))
                .thenReturn(Optional.of(saved));

        NotificationSendEvent mail = capturedMail();
        String url = purpose == TokenPurpose.PASSWORD_RESET
                ? mail.getParams().get("resetUrl")
                : mail.getParams().get("verificationUrl");
        return url.substring(url.indexOf("token=") + "token=".length());
    }

    private VerificationToken grant(TokenPurpose purpose) {
        return VerificationToken.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .purpose(purpose)
                .tokenHash("0".repeat(64))
                .expiresAt(Instant.now().plusSeconds(600))
                .createdAt(Instant.now())
                .build();
    }

    private NotificationSendEvent capturedMail() {
        verify(outboxService, org.mockito.Mockito.atLeastOnce())
                .append(anyString(), anyString(), messageCaptor.capture());
        return messageCaptor.getAllValues().stream()
                .filter(NotificationSendEvent.class::isInstance)
                .map(NotificationSendEvent.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no notification was queued"));
    }
}
