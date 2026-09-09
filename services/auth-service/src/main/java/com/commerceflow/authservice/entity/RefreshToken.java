package com.commerceflow.authservice.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Server-side half of a refresh token.
 *
 * <p>The id is the {@code jti} claim of the issued token and only a SHA-256 hash of the token
 * value is stored, so a database leak cannot be replayed against the API. Refresh is a rotation:
 * using a token revokes it and issues a new pair.
 */
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_token_user", columnList = "user_id"),
        @Index(name = "idx_refresh_token_expires", columnList = "expires_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {

    /** Equals the {@code jti} claim of the token it represents. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Hex encoded SHA-256 of the token value. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Set when this token was rotated, so a replay can be traced to its successor. */
    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isUsable() {
        return !revoked && !isExpired();
    }

    public void revoke(UUID successor) {
        this.revoked = true;
        this.revokedAt = Instant.now();
        this.replacedBy = successor;
    }
}
