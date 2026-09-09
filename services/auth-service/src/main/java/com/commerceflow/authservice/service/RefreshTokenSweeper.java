package com.commerceflow.authservice.service;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.authservice.config.AuthProperties;
import com.commerceflow.authservice.repository.RefreshTokenRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Drops refresh tokens that expired long enough ago to be of no further use.
 *
 * <h2>Why the table needs sweeping at all</h2>
 *
 * <p>Every sign-in on every device leaves a row, and nothing ever removed them. A busy customer
 * signing in on a phone, a laptop and a tablet produces three a week; multiplied by a customer
 * base and a year, the table becomes the largest thing in the auth database and every token
 * lookup pays for it.
 *
 * <p>It is also a record of when each customer signed in and from what — worth keeping while it
 * can answer a question, not worth keeping forever.
 *
 * <h2>Expired, not revoked</h2>
 *
 * <p>Only rows whose {@code expiresAt} is well in the past are deleted. A <em>revoked</em> token
 * that has not expired yet stays: it is what makes a sign-out actually stick, and deleting it
 * early would turn "this session was ended" into "no such session", which the refresh endpoint
 * cannot tell apart from a token it has never seen.
 *
 * <p>Past its expiry the distinction stops mattering, because an expired token is refused on the
 * timestamp before anything looks it up.
 *
 * <p>The grace period on top of the expiry exists so a support question asked the next morning
 * still has something to look at. Seven days is long enough for that and short enough that the
 * table stays a working set rather than a history.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenSweeper {

    private final RefreshTokenRepository refreshTokens;
    private final AuthProperties properties;

    @Scheduled(
            fixedDelayString = "${commerceflow.auth.refresh-token-sweep-interval-ms:3600000}",
            initialDelayString = "${commerceflow.auth.refresh-token-sweep-interval-ms:3600000}")
    @Transactional
    public void purgeExpiredTokens() {
        try {
            Instant cutoff = Instant.now().minus(properties.getRefreshTokenRetention());
            int deleted = refreshTokens.deleteAllExpiredBefore(cutoff);

            if (deleted > 0) {
                log.info("Refresh token sweep removed {} row(s) expired before {}", deleted,
                        cutoff);
            }
        } catch (Exception ex) {
            // Housekeeping. Failing it must never take sign-in down; the next hour retries.
            log.error("Refresh token sweep failed", ex);
        }
    }
}
