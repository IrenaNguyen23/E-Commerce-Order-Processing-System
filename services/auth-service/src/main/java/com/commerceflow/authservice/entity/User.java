package com.commerceflow.authservice.entity;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A registered account. */
@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Always stored lower-cased; the unique index is what makes registration race-safe. */
    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    /** BCrypt hash. Never logged, never returned by any endpoint. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /**
     * Whether the address has been proven reachable.
     *
     * <p>Separate from {@link #enabled}: an unverified account is not a disabled one. Whether an
     * unverified user may sign in is a policy switch
     * ({@code commerceflow.security.require-verified-email}), not a property of the account.
     */
    @Column(name = "email_verified", nullable = false)
    @lombok.Builder.Default
    private boolean emailVerified = false;

    /**
     * Created by a guest checkout rather than by somebody registering.
     *
     * <p>The account is real and works normally; the flag exists so the difference is visible.
     * A guest has no password they know, so signing in is impossible until they set one through
     * the ordinary reset flow — at which point their order history is already there.
     *
     * <p>It also lets an operator tell a genuine sign-up from a checkout, which otherwise looks
     * identical in the accounts list and makes every registration metric wrong.
     */
    @Column(name = "guest", nullable = false)
    @lombok.Builder.Default
    private boolean guest = false;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    @Builder.Default
    private Set<Role> roles = EnumSet.of(Role.CUSTOMER);

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** @return the role names without the {@code ROLE_} prefix, for the JWT claim. */
    public Set<String> roleNames() {
        return roles.stream().map(Enum::name).collect(java.util.stream.Collectors.toSet());
    }
}
