package com.commerceflow.authservice.service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.commerceflow.authservice.config.AuthProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Slows down somebody guessing a password.
 *
 * <h2>Per account, because the gateway already does per IP</h2>
 *
 * <p>The gateway rate-limits {@code /api/auth/**} by IP, which stops one machine hammering the
 * endpoint. It does nothing about the attack that actually works: a few attempts per minute per
 * address, spread across a botnet, against one account. Every request looks reasonable; only the
 * account sees them all.
 *
 * <p>So the counter is keyed on the account. The two limits catch different attacks and neither
 * replaces the other.
 *
 * <h2>Locked, not disabled</h2>
 *
 * <p>A lock is a Redis key with a TTL. It expires on its own, and it does not touch
 * {@code users.enabled} — which matters, because {@code enabled} is an operator's decision about
 * an account and a lock is a temporary consequence of traffic. Conflating them means an attacker
 * can permanently disable anybody's account by typing the wrong password often enough, and an
 * administrator has to undo it by hand.
 *
 * <h2>What it costs when Redis is down</h2>
 *
 * <p>Nothing, and that is deliberate. If Redis is unreachable the throttle lets the attempt
 * through rather than refusing it — see {@link #isLocked}. Refusing would turn a cache outage into
 * a total sign-in outage, which is a worse failure than a temporarily weakened brute-force defence
 * that still has the gateway's per-IP limit behind it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginThrottle {

    private static final String FAILURE_KEY = "login:failures:";
    private static final String LOCK_KEY = "login:locked:";

    private final StringRedisTemplate redis;
    private final AuthProperties properties;

    /**
     * Whether this account is currently refusing attempts.
     *
     * @return {@code false} when Redis cannot be reached — see the class comment for why a cache
     *     outage must not become a sign-in outage
     */
    public boolean isLocked(String email) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(LOCK_KEY + key(email)));
        } catch (RuntimeException ex) {
            log.warn("Could not read the login throttle for an account; allowing the attempt", ex);
            return false;
        }
    }

    /** How much longer the lock has, for the message the customer is shown. */
    public Duration remainingLock(String email) {
        try {
            Long seconds = redis.getExpire(LOCK_KEY + key(email), TimeUnit.SECONDS);
            return seconds == null || seconds < 0 ? Duration.ZERO : Duration.ofSeconds(seconds);
        } catch (RuntimeException ex) {
            return Duration.ZERO;
        }
    }

    /**
     * Records a wrong password, and locks the account once there have been enough.
     *
     * <p>The failure counter has its own, longer TTL than the lock. Without it, somebody could sit
     * one attempt below the threshold indefinitely: the count would expire between tries and the
     * lock would never fire.
     *
     * @return {@code true} when this attempt was the one that locked it
     */
    public boolean recordFailure(String email) {
        try {
            String counter = FAILURE_KEY + key(email);
            Long failures = redis.opsForValue().increment(counter);

            if (failures != null && failures == 1L) {
                redis.expire(counter, properties.getLoginFailureWindow());
            }

            if (failures != null && failures >= properties.getMaxLoginFailures()) {
                redis.opsForValue().set(LOCK_KEY + key(email), "1",
                        properties.getLoginLockDuration());
                redis.delete(counter);

                // At warn, and with the account rather than the address: this is the line
                // somebody greps for after a customer says they cannot get in.
                log.warn("Locked sign-in for an account after {} failed attempts; unlocks in {}",
                        failures, properties.getLoginLockDuration());
                return true;
            }
            return false;
        } catch (RuntimeException ex) {
            log.warn("Could not record a failed sign-in; the throttle is not counting", ex);
            return false;
        }
    }

    /** Clears the count after a successful sign-in. */
    public void recordSuccess(String email) {
        try {
            redis.delete(FAILURE_KEY + key(email));
        } catch (RuntimeException ex) {
            log.debug("Could not clear the login failure counter", ex);
        }
    }

    /**
     * Releases a lock by hand.
     *
     * <p>Exists because support will be asked to. The alternative — telling a customer to wait
     * fifteen minutes — is fine for a real lockout and infuriating for somebody who simply
     * mistyped their password four times on a phone keyboard.
     */
    public void unlock(String email) {
        try {
            redis.delete(LOCK_KEY + key(email));
            redis.delete(FAILURE_KEY + key(email));
        } catch (RuntimeException ex) {
            log.warn("Could not clear a sign-in lock", ex);
        }
    }

    /**
     * The Redis key for an address.
     *
     * <p>Lower-cased so that {@code Ada@example.com} and {@code ada@example.com} share a counter —
     * otherwise the throttle is defeated by changing the case of a letter.
     */
    private static String key(String email) {
        return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
