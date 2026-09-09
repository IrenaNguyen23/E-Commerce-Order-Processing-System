package com.commerceflow.authservice.entity;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A saved address in a customer's address book.
 *
 * <h2>This is a convenience, not a system of record</h2>
 *
 * <p>An order never points at one of these rows. It copies the fields it needs at checkout and
 * keeps them, the same way it copies the product name and the price. That is what makes
 * deleting an address safe: no invoice, no shipping label and no tax calculation
 * anywhere is reading it. The address book exists so a customer does not retype their street every
 * time, and for nothing else.
 *
 * <p>The alternative — an {@code address_id} on the order — looks tidier in the schema and is
 * wrong: correcting a typo in your street would silently rewrite where last year's parcels were
 * sent, and deleting an old address would orphan the orders that quoted it.
 *
 * <h2>Country is not decoration</h2>
 *
 * <p>{@link #countryCode} is ISO-3166 alpha-2, upper-cased, and it is the input to the tax rate and
 * the shipping zone. A free-text country field would mean "Netherlands", "NL", "netherlands" and
 * "The Netherlands" priced differently, which is why the whole address is structured rather than
 * one line of text.
 */
@Entity
@Table(name = "user_addresses", indexes = {
        @Index(name = "idx_user_addresses_user", columnList = "user_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAddress {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** What the customer calls it: "Home", "Work". Theirs to choose, ours to display. */
    @Column(name = "label", length = 50)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    @Builder.Default
    private AddressType type = AddressType.BOTH;

    /** Who the courier asks for. Not always the account holder. */
    @Column(name = "recipient_name", nullable = false, length = 150)
    private String recipientName;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "line1", nullable = false, length = 200)
    private String line1;

    @Column(name = "line2", length = 200)
    private String line2;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    /** State, province or region. Optional because plenty of countries have no such thing. */
    @Column(name = "region", length = 100)
    private String region;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    /** ISO-3166 alpha-2, upper case. Drives the tax rate and the shipping zone. */
    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    /**
     * At most one per user is true, and the database enforces it with a partial unique index.
     *
     * <p>Clearing the previous default in the service is what makes the common path work; the
     * index is what makes two simultaneous "make this my default" requests fail loudly instead of
     * leaving a customer with two defaults and a checkout form that picks arbitrarily.
     */
    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private boolean isDefault = false;

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
        normalise();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
        normalise();
    }

    private void normalise() {
        if (countryCode != null) {
            // Locale.ROOT, not the default locale. Under a Turkish locale "in".toUpperCase()
            // yields "İN" and India stops matching any tax rate — a bug that only appears on
            // servers configured in one country.
            this.countryCode = countryCode.trim().toUpperCase(Locale.ROOT);
        }
    }
}
