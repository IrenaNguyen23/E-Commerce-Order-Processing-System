package com.commerceflow.authservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerceflow.authservice.config.AuthProperties;
import com.commerceflow.authservice.repository.RefreshTokenRepository;

/**
 * Housekeeping that has to be both correct and harmless.
 *
 * <p>The cutoff is the whole behaviour: too far forward and a live session is deleted, too far
 * back and the table never stops growing. The other test is that a failure here cannot take
 * sign-in down with it.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenSweeperTest {

    @Mock
    private RefreshTokenRepository refreshTokens;

    private AuthProperties properties;
    private RefreshTokenSweeper sweeper;

    @BeforeEach
    void setUp() {
        properties = new AuthProperties();
        sweeper = new RefreshTokenSweeper(refreshTokens, properties);
    }

    @Test
    @DisplayName("deletes tokens that expired a whole retention period ago")
    void cutoffIsRetentionBeforeNow() {
        properties.setRefreshTokenRetention(Duration.ofDays(7));
        when(refreshTokens.deleteAllExpiredBefore(any())).thenReturn(3);

        Instant before = Instant.now();
        sweeper.purgeExpiredTokens();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(refreshTokens).deleteAllExpiredBefore(cutoff.capture());

        // Seven days back, give or take the time the test itself took. Pinning the arithmetic
        // rather than the value, because the bug this catches is a sign flip -- a cutoff in the
        // future would delete every session in the system.
        assertThat(cutoff.getValue())
                .isBefore(before.minus(Duration.ofDays(6)))
                .isAfter(before.minus(Duration.ofDays(8)));
    }

    @Test
    @DisplayName("a failing sweep does not escape")
    void failureIsSwallowed() {
        when(refreshTokens.deleteAllExpiredBefore(any()))
                .thenThrow(new RuntimeException("connection reset"));

        // Housekeeping running into a locked table at four in the morning must not become an
        // outage. The next hour tries again.
        assertThatCode(sweeper::purgeExpiredTokens).doesNotThrowAnyException();
    }
}
