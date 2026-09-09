package com.commerceflow.authservice.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.commerceflow.authservice.entity.TokenPurpose;
import com.commerceflow.authservice.entity.VerificationToken;

/** Persistence port for emailed, single-use grants. */
@Repository
public interface VerificationTokenRepository extends JpaRepository<VerificationToken, UUID> {

    /** The only lookup path: a token is found by its digest, or it is not valid. */
    Optional<VerificationToken> findByTokenHashAndPurpose(String tokenHash, TokenPurpose purpose);

    /**
     * Burns every outstanding token a user holds for one purpose.
     *
     * <p>Called before issuing a new one, so asking for a second reset link silently kills the
     * first. Without that, every request a user makes leaves another working key to their account
     * lying in their inbox.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE VerificationToken t
               SET t.consumedAt = :now
             WHERE t.userId = :userId
               AND t.purpose = :purpose
               AND t.consumedAt IS NULL
            """)
    int consumeOutstanding(@Param("userId") UUID userId,
                           @Param("purpose") TokenPurpose purpose,
                           @Param("now") Instant now);

    /** Drops tokens that expired long enough ago to be of no further interest. */
    @Modifying
    @Query("DELETE FROM VerificationToken t WHERE t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
