package com.commerceflow.authservice.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single-use, expiring grant that was emailed to a user.
 *
 * <p>Only the SHA-256 digest of the token is stored, for the same reason passwords are hashed:
 * a database dump must not hand the reader a working password-reset link for every account in it.
 * The token itself exists in exactly one place — the email that was sent.
 */
@Entity
@Table(name = "verification_tokens", indexes = {
        @Index(name = "idx_verification_user_purpose", columnList = "user_id, purpose"),
        @Index(name = "idx_verification_expires", columnList = "expires_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerificationToken {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32)
    private TokenPurpose purpose;

    /** Hex SHA-256 of the token value. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Set the moment the token is used; a non-null value makes it permanently unusable. */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * @return whether this token can still be redeemed right now
     */
    public boolean isRedeemable() {
        return consumedAt == null && expiresAt.isAfter(Instant.now());
    }

    /** Burns the token. Called inside the same transaction as whatever it authorised. */
    public void consume() {
        this.consumedAt = Instant.now();
    }
}
