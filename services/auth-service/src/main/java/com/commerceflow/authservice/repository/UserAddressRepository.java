package com.commerceflow.authservice.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.commerceflow.authservice.entity.UserAddress;

public interface UserAddressRepository extends JpaRepository<UserAddress, UUID> {

    /** Default first, then most recently touched — the order the checkout form should offer. */
    List<UserAddress> findByUserIdOrderByIsDefaultDescUpdatedAtDesc(UUID userId);

    /**
     * Scoped by owner on purpose. Looking a row up by id alone and checking the owner afterwards
     * is the same query written in a way that can be got wrong later.
     */
    Optional<UserAddress> findByIdAndUserId(UUID id, UUID userId);

    Optional<UserAddress> findByUserIdAndIsDefaultTrue(UUID userId);

    long countByUserId(UUID userId);

    /**
     * Clears every default for a user except the one being promoted.
     *
     * <p>A bulk update rather than a read-modify-write loop: it is one statement, so there is no
     * window in which the customer has zero defaults, and it does not depend on how many addresses
     * were wrongly flagged if the invariant has already been broken.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserAddress a SET a.isDefault = false, a.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE a.userId = :userId AND a.isDefault = true AND a.id <> :keep")
    int clearOtherDefaults(@Param("userId") UUID userId, @Param("keep") UUID keep);
}
