package com.commerceflow.authservice.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.commerceflow.authservice.entity.Role;
import com.commerceflow.authservice.entity.User;

/** Persistence port for accounts. */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /**
     * The back-office account list.
     *
     * <p>Every filter is optional and applied only when supplied, so one query serves the
     * unfiltered list and every combination of narrowing.
     *
     * <p>Roles are joined rather than fetched separately: without it, listing twenty accounts
     * issues twenty-one queries, which is invisible in a demo and very visible at ten thousand
     * users.
     */
    @Query("""
            SELECT DISTINCT u FROM User u
            LEFT JOIN u.roles r
            WHERE (:search IS NULL
                   OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :search, '%')))
              AND (:role IS NULL OR r = :role)
              AND (:enabled IS NULL OR u.enabled = :enabled)
            """)
    Page<User> search(@Param("search") String search,
                      @Param("role") Role role,
                      @Param("enabled") Boolean enabled,
                      Pageable pageable);

    /** How many accounts still hold a given role. Guards the last-administrator rule. */
    @Query("SELECT COUNT(DISTINCT u) FROM User u JOIN u.roles r WHERE r = :role AND u.enabled = TRUE")
    long countEnabledWithRole(@Param("role") Role role);
}
