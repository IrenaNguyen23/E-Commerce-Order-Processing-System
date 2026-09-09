package com.commerceflow.authservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.commerceflow.authservice.config.AuthProperties;

/**
 * The per-account sign-in throttle.
 *
 * <p>Two things here matter more than the counting. The throttle must not become a way to find out
 * which addresses have accounts, and it must not turn a Redis outage into a sign-in outage — both
 * are ways a defence becomes a worse problem than the thing it defends against.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoginThrottleTest {

    private static final String EMAIL = "ada@commerceflow.io";

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> values;

    private LoginThrottle throttle;
    private AuthProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AuthProperties();
        throttle = new LoginThrottle(redis, properties);
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    @DisplayName("a wrong password below the threshold does not lock")
    void underThresholdDoesNotLock() {
        when(values.increment(anyString())).thenReturn(3L);

        assertThat(throttle.recordFailure(EMAIL)).isFalse();
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("reaching the threshold locks the account for a bounded time")
    void thresholdLocks() {
        when(values.increment(anyString())).thenReturn(5L);

        assertThat(throttle.recordFailure(EMAIL)).isTrue();
        // A key with a TTL, so it releases itself. Nobody has to remember to unlock it, and an
        // attacker cannot leave somebody permanently shut out.
        verify(values).set(eq("login:locked:" + EMAIL), eq("1"),
                eq(properties.getLoginLockDuration()));
    }

    @Test
    @DisplayName("the first failure starts a window, so a slow attacker still trips it")
    void firstFailureStartsTheWindow() {
        when(values.increment(anyString())).thenReturn(1L);

        throttle.recordFailure(EMAIL);

        // Without a window the count expires between attempts and the lock never fires — which is
        // exactly the attack this exists to stop.
        verify(redis).expire("login:failures:" + EMAIL, properties.getLoginFailureWindow());
    }

    @Test
    @DisplayName("the counter is keyed case-insensitively")
    void caseDoesNotDefeatTheCounter() {
        when(values.increment(anyString())).thenReturn(1L);

        throttle.recordFailure("  Ada@CommerceFlow.IO  ");

        // Otherwise changing a letter's case is a way round the whole thing.
        verify(values).increment("login:failures:" + EMAIL);
    }

    @Test
    @DisplayName("a successful sign-in clears the count but not somebody else's")
    void successClearsTheCount() {
        throttle.recordSuccess(EMAIL);

        verify(redis).delete("login:failures:" + EMAIL);
    }

    @Test
    @DisplayName("an operator can release a lock by hand")
    void supportCanUnlock() {
        throttle.unlock(EMAIL);

        // Support will be asked to. "Wait fifteen minutes" is a fine answer to a real attack and
        // an infuriating one to somebody who mistyped on a phone keyboard.
        verify(redis).delete("login:locked:" + EMAIL);
        verify(redis).delete("login:failures:" + EMAIL);
    }

    @Test
    @DisplayName("Redis being down lets the attempt through rather than refusing everybody")
    void redisOutageFailsOpen() {
        when(redis.hasKey(anyString()))
                .thenThrow(new RedisConnectionFailureException("Redis is gone"));

        // Deliberate. Failing closed turns a cache outage into a total sign-in outage, which is a
        // worse failure than a temporarily weakened brute-force defence that still has the
        // gateway's per-IP limit behind it.
        assertThat(throttle.isLocked(EMAIL)).isFalse();
    }

    @Test
    @DisplayName("Redis being down does not throw out of the failure path either")
    void recordingSurvivesAnOutage() {
        when(values.increment(anyString()))
                .thenThrow(new RedisConnectionFailureException("Redis is gone"));

        // A throttle that throws would turn a wrong password into a 500 rather than a 401.
        assertThat(throttle.recordFailure(EMAIL)).isFalse();
    }

    @Test
    @DisplayName("the remaining lock time is reported for the message a customer sees")
    void remainingTimeIsReadable() {
        when(redis.getExpire("login:locked:" + EMAIL, TimeUnit.SECONDS)).thenReturn(600L);

        assertThat(throttle.remainingLock(EMAIL)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("no lock reports no remaining time rather than a negative one")
    void missingKeyReportsZero() {
        when(redis.getExpire("login:locked:" + EMAIL, TimeUnit.SECONDS)).thenReturn(-2L);

        // Redis returns -2 for a key that does not exist and -1 for one with no TTL. Passing
        // either straight through would produce "try again in -2 minutes".
        assertThat(throttle.remainingLock(EMAIL)).isEqualTo(Duration.ZERO);
    }
}
