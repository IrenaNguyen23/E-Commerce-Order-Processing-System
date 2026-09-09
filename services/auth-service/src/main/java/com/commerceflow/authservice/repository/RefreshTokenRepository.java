package com.commerceflow.authservice.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.commerceflow.authservice.entity.RefreshToken;

/** Persistence port for refresh tokens. */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByIdAndRevokedFalse(UUID id);

    /** Revokes every live session of a user; used on logout and on refresh-token reuse. */
    @Modifying
    @Query("""
            UPDATE RefreshToken t
               SET t.revoked = true, t.revokedAt = :now
             WHERE t.userId = :userId AND t.revoked = false
            """)
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Housekeeping: expired tokens carry no value once they can no longer be presented. */
    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :before")
    int deleteAllExpiredBefore(@Param("before") Instant before);

    long countByUserIdAndRevokedFalse(UUID userId);
}
