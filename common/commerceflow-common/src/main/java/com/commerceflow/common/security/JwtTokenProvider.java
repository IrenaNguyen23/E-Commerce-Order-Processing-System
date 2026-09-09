package com.commerceflow.common.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.UnauthorizedException;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

/**
 * Issues and verifies the platform's JWTs.
 *
 * <p>Deliberately dependency free apart from JJWT so both the servlet services and the reactive
 * gateway can share exactly the same verification logic.
 */
@Slf4j
public class JwtTokenProvider {

    static final String CLAIM_EMAIL = "email";
    static final String CLAIM_ROLES = "roles";
    static final String CLAIM_TOKEN_TYPE = "typ";

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        byte[] keyBytes = properties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "commerceflow.security.jwt.secret must be at least 32 bytes for HS256, was "
                            + keyBytes.length);
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /** Issues a short lived access token. */
    public String generateAccessToken(UUID userId, String email, Set<String> roles) {
        return generate(userId, email, roles, TokenType.ACCESS, properties.getAccessTokenTtl().toSeconds(),
                UUID.randomUUID().toString());
    }

    /** Issues a long lived refresh token; {@code tokenId} is persisted by Auth Service. */
    public String generateRefreshToken(UUID userId, String email, String tokenId) {
        return generate(userId, email, Set.of(), TokenType.REFRESH,
                properties.getRefreshTokenTtl().toSeconds(), tokenId);
    }

    private String generate(UUID userId, String email, Set<String> roles, TokenType type,
                            long ttlSeconds, String tokenId) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(ttlSeconds);
        return Jwts.builder()
                .id(tokenId)
                .subject(userId.toString())
                .issuer(properties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLES, roles == null ? List.of() : List.copyOf(roles))
                .claim(CLAIM_TOKEN_TYPE, type.name())
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verifies signature, issuer and expiry.
     *
     * @throws UnauthorizedException when the token is expired, malformed or not signed by us
     */
    public Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.getIssuer())
                    .clockSkewSeconds(properties.getClockSkew().toSeconds())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException ex) {
            throw new UnauthorizedException(ErrorCode.TOKEN_EXPIRED, "Access token has expired");
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected JWT: {}", ex.getMessage());
            throw new UnauthorizedException(ErrorCode.TOKEN_INVALID, "Token is invalid");
        }
    }

    /**
     * Verifies the token and maps it to the caller identity.
     *
     * @param expectedType token flavour the caller requires
     * @throws UnauthorizedException when verification fails or the flavour does not match
     */
    public AuthenticatedUser authenticate(String token, TokenType expectedType) {
        Claims claims = parseClaims(token);
        String actualType = claims.get(CLAIM_TOKEN_TYPE, String.class);
        if (!expectedType.name().equals(actualType)) {
            throw new UnauthorizedException(ErrorCode.TOKEN_INVALID,
                    "Expected a " + expectedType.name().toLowerCase() + " token");
        }
        return new AuthenticatedUser(
                UUID.fromString(claims.getSubject()),
                claims.get(CLAIM_EMAIL, String.class),
                extractRoles(claims),
                claims.getId());
    }

    /** Convenience for the common case: an access token. */
    public AuthenticatedUser authenticate(String token) {
        return authenticate(token, TokenType.ACCESS);
    }

    /** @return {@code true} when the token verifies as the requested flavour. */
    public boolean isValid(String token, TokenType expectedType) {
        try {
            authenticate(token, expectedType);
            return true;
        } catch (UnauthorizedException ex) {
            return false;
        }
    }

    /** Strips the {@code Bearer } prefix; returns {@code null} when the header is unusable. */
    public static String resolveBearerToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        String trimmed = authorizationHeader.trim();
        if (trimmed.length() <= SecurityHeaders.BEARER_PREFIX.length()
                || !trimmed.regionMatches(true, 0, SecurityHeaders.BEARER_PREFIX, 0,
                        SecurityHeaders.BEARER_PREFIX.length())) {
            return null;
        }
        String token = trimmed.substring(SecurityHeaders.BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private static Set<String> extractRoles(Claims claims) {
        Object raw = claims.get(CLAIM_ROLES);
        if (raw instanceof List<?> list) {
            Set<String> roles = new LinkedHashSet<>();
            for (Object element : list) {
                if (element != null) {
                    roles.add(String.valueOf(element));
                }
            }
            return Set.copyOf(roles);
        }
        return Set.of();
    }

    /** Expiry instant of a verified token; used to size the logout blacklist TTL. */
    public Instant expiryOf(String token) {
        Date expiration = parseClaims(token).getExpiration();
        return expiration == null ? Instant.now() : expiration.toInstant();
    }
}
