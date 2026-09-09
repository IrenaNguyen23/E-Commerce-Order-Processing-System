package com.commerceflow.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.UnauthorizedException;

class JwtTokenProviderTest {

    private static final String SECRET = "commerceflow-unit-test-secret-key-of-at-least-32-bytes";

    private JwtProperties properties;
    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setIssuer("commerceflow");
        properties.setAccessTokenTtl(Duration.ofMinutes(15));
        properties.setRefreshTokenTtl(Duration.ofDays(7));
        properties.setClockSkew(Duration.ofSeconds(0));
        provider = new JwtTokenProvider(properties);
    }

    @Test
    @DisplayName("an access token round trips subject, email and roles")
    void accessTokenRoundTrip() {
        UUID userId = UUID.randomUUID();
        String token = provider.generateAccessToken(userId, "ada@commerceflow.io",
                Set.of(SecurityHeaders.ROLE_CUSTOMER));

        AuthenticatedUser user = provider.authenticate(token);

        assertThat(user.userId()).isEqualTo(userId);
        assertThat(user.email()).isEqualTo("ada@commerceflow.io");
        assertThat(user.roles()).containsExactly(SecurityHeaders.ROLE_CUSTOMER);
        assertThat(user.tokenId()).isNotBlank();
        assertThat(user.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("a refresh token is rejected where an access token is required")
    void refreshTokenIsNotAnAccessToken() {
        String refresh = provider.generateRefreshToken(UUID.randomUUID(), "ada@commerceflow.io",
                UUID.randomUUID().toString());

        assertThatThrownBy(() -> provider.authenticate(refresh, TokenType.ACCESS))
                .isInstanceOf(UnauthorizedException.class)
                .extracting(ex -> ((UnauthorizedException) ex).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);

        assertThat(provider.isValid(refresh, TokenType.REFRESH)).isTrue();
    }

    @Test
    @DisplayName("an expired token yields TOKEN_EXPIRED")
    void expiredTokenIsRejected() {
        properties.setAccessTokenTtl(Duration.ofSeconds(-60));
        JwtTokenProvider expiringProvider = new JwtTokenProvider(properties);
        String token = expiringProvider.generateAccessToken(UUID.randomUUID(), "ada@commerceflow.io",
                Set.of(SecurityHeaders.ROLE_CUSTOMER));

        assertThatThrownBy(() -> expiringProvider.authenticate(token))
                .isInstanceOf(UnauthorizedException.class)
                .extracting(ex -> ((UnauthorizedException) ex).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("a token signed with another secret is rejected")
    void foreignSignatureIsRejected() {
        JwtProperties foreign = new JwtProperties();
        foreign.setSecret("a-completely-different-secret-key-with-32-bytes-plus");
        foreign.setIssuer("commerceflow");
        String token = new JwtTokenProvider(foreign)
                .generateAccessToken(UUID.randomUUID(), "mallory@evil.io", Set.of("ADMIN"));

        assertThatThrownBy(() -> provider.authenticate(token))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("a token from another issuer is rejected")
    void foreignIssuerIsRejected() {
        JwtProperties foreign = new JwtProperties();
        foreign.setSecret(SECRET);
        foreign.setIssuer("somebody-else");
        String token = new JwtTokenProvider(foreign)
                .generateAccessToken(UUID.randomUUID(), "mallory@evil.io", Set.of("ADMIN"));

        assertThatThrownBy(() -> provider.authenticate(token))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("a secret shorter than 32 bytes fails fast at construction")
    void weakSecretIsRejected() {
        JwtProperties weak = new JwtProperties();
        weak.setSecret("too-short");

        assertThatThrownBy(() -> new JwtTokenProvider(weak))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    @DisplayName("the Bearer prefix is parsed case insensitively and defensively")
    void bearerTokenResolution() {
        assertThat(JwtTokenProvider.resolveBearerToken("Bearer abc.def.ghi")).isEqualTo("abc.def.ghi");
        assertThat(JwtTokenProvider.resolveBearerToken("bearer abc.def.ghi")).isEqualTo("abc.def.ghi");
        assertThat(JwtTokenProvider.resolveBearerToken("  Bearer   abc  ")).isEqualTo("abc");
        assertThat(JwtTokenProvider.resolveBearerToken("Basic abc")).isNull();
        assertThat(JwtTokenProvider.resolveBearerToken("Bearer ")).isNull();
        assertThat(JwtTokenProvider.resolveBearerToken(null)).isNull();
    }
}
