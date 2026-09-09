package com.commerceflow.authservice.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis backed deny-list of access tokens that were invalidated before their natural expiry.
 *
 * <p>Access tokens are short lived and self-contained, so the only way to end a session early is
 * to remember the revoked {@code jti} until the token would have expired anyway — which is
 * exactly how long the Redis key lives.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String KEY_PREFIX = "auth:blacklist:";
    private static final String VALUE = "revoked";

    private final StringRedisTemplate redisTemplate;

    /** Denies the token id until {@code expiresAt}; a already-expired token is ignored. */
    public void revoke(String tokenId, Instant expiresAt) {
        if (tokenId == null) {
            return;
        }
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redisTemplate.opsForValue().set(KEY_PREFIX + tokenId, VALUE, ttl);
        log.debug("Access token {} revoked for the next {}s", tokenId, ttl.toSeconds());
    }

    /**
     * @return {@code true} when the token has been revoked. Fails open on a Redis outage: the
     *     alternative is refusing every request, and the tokens expire within minutes anyway.
     */
    public boolean isRevoked(String tokenId) {
        if (tokenId == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + tokenId));
        } catch (RuntimeException ex) {
            log.warn("Blacklist lookup failed, allowing the request: {}", ex.getMessage());
            return false;
        }
    }
}
