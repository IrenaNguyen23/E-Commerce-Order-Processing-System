package com.commerceflow.authservice.service;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.authservice.config.AuthProperties;
import com.commerceflow.authservice.repository.VerificationTokenRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Drops verification tokens once they are long past useful.
 *
 * <p>Every reset request and every resend leaves a row behind. They are small, but they are also
 * a list of who asked to recover an account and when — the kind of data that is only a liability
 * once it has stopped being useful. Deleting is the simplest way to stop holding it.
 *
 * <p>Only expired rows go. A consumed token that has not expired yet is kept deliberately: if a
 * customer reports that a link "did not work", the row is the difference between knowing it was
 * already used and guessing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationTokenSweeper {

    private final VerificationTokenRepository tokenRepository;
    private final AuthProperties properties;

    @Scheduled(
            fixedDelayString = "${commerceflow.auth.token-sweep-interval-ms:3600000}",
            initialDelayString = "${commerceflow.auth.token-sweep-interval-ms:3600000}")
    @Transactional
    public void purgeExpiredTokens() {
        try {
            Instant cutoff = Instant.now().minus(properties.getVerificationTokenRetention());
            int deleted = tokenRepository.deleteExpiredBefore(cutoff);

            if (deleted > 0) {
                log.info("Verification token sweep removed {} row(s) expired before {}", deleted,
                        cutoff);
            }
        } catch (Exception ex) {
            // Housekeeping. Failing it must never take the service down; the next hour retries.
            log.error("Verification token sweep failed", ex);
        }
    }
}
