package com.commerceflow.authservice.security;

import com.commerceflow.authservice.service.TokenBlacklistService;
import com.commerceflow.common.security.TokenRevocationChecker;

import lombok.RequiredArgsConstructor;

/**
 * Auth Service is the one service that can see the logout deny-list, so it overrides the
 * no-op {@code TokenRevocationChecker} contributed by {@code commerceflow-common}.
 */
@RequiredArgsConstructor
public class RedisTokenRevocationChecker implements TokenRevocationChecker {

    private final TokenBlacklistService blacklistService;

    @Override
    public boolean isRevoked(String tokenId) {
        return blacklistService.isRevoked(tokenId);
    }
}
