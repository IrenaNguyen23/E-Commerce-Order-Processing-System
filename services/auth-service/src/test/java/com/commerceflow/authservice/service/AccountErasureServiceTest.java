package com.commerceflow.authservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.commerceflow.authservice.entity.Role;
import com.commerceflow.authservice.entity.User;
import com.commerceflow.authservice.repository.RefreshTokenRepository;
import com.commerceflow.authservice.repository.UserAddressRepository;
import com.commerceflow.authservice.repository.UserRepository;
import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.event.UserErasedEvent;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.common.outbox.OutboxService;
import com.commerceflow.common.security.AuthenticatedUser;

/**
 * Forgetting a customer.
 *
 * <p>Erasure fails in two opposite directions and both are bad: too little leaves personal data
 * behind, and too much destroys a financial record. The tests below pin the line between them,
 * and the one refusal that exists because erasure cannot be undone.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountErasureServiceTest {

    @Mock
    private UserRepository users;

    @Mock
    private UserAddressRepository addresses;

    @Mock
    private RefreshTokenRepository refreshTokens;

    @Mock
    private OutboxService outboxService;

    @Mock
    private AuditService auditService;

    private AccountErasureService service;
    private User customer;
    private AuthenticatedUser self;
    private AuthenticatedUser admin;

    @BeforeEach
    void setUp() {
        service = new AccountErasureService(users, addresses, refreshTokens, outboxService,
                auditService, new BCryptPasswordEncoder(4));

        UUID id = UUID.randomUUID();
        customer = User.builder()
                .id(id).email("ada@commerceflow.io").fullName("Ada Lovelace")
                .phone("+31 20 123 4567").passwordHash("$2a$10$known")
                .enabled(true).emailVerified(true)
                .roles(EnumSet.of(Role.CUSTOMER))
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();

        self = new AuthenticatedUser(id, "ada@commerceflow.io", Set.of("CUSTOMER"),
                UUID.randomUUID().toString());
        admin = new AuthenticatedUser(UUID.randomUUID(), "ops@commerceflow.io", Set.of("ADMIN"),
                UUID.randomUUID().toString());

        when(users.findById(id)).thenReturn(Optional.of(customer));
        when(users.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        when(addresses.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(id)).thenReturn(List.of());
    }

    @Test
    @DisplayName("the account survives, and identifies nobody")
    void accountIsAnonymisedNotDeleted() {
        service.erase(customer.getId(), self);

        // Deleting the row would take the orders with it, and an accountant would find a hole
        // where a sale used to be.
        verify(users, never()).delete(any());
        assertThat(customer.getEmail()).endsWith("@erased.invalid");
        assertThat(customer.getFullName()).isEqualTo("Deleted account");
        assertThat(customer.getPhone()).isNull();
    }

    @Test
    @DisplayName("the placeholder address is in a domain nobody can ever register")
    void placeholderIsNonRoutable() {
        service.erase(customer.getId(), self);

        // RFC 2606 reserves .invalid. A made-up domain like deleted.local could be bought
        // tomorrow, and then an anonymised address is a real person's.
        assertThat(customer.getEmail()).endsWith(".invalid");
    }

    @Test
    @DisplayName("the account can never be signed into again")
    void signingInBecomesImpossible() {
        service.erase(customer.getId(), self);

        assertThat(customer.isEnabled()).isFalse();
        // A hash nobody knows rather than null, so password comparison simply fails instead of
        // depending on every future code path checking whether an account is erased.
        assertThat(customer.getPasswordHash()).startsWith("$2").isNotEqualTo("$2a$10$known");
        verify(refreshTokens).revokeAllForUser(eq(customer.getId()), any(Instant.class));
    }

    @Test
    @DisplayName("saved addresses are deleted outright rather than blanked")
    void addressesGoEntirely() {
        var address = com.commerceflow.authservice.entity.UserAddress.builder()
                .id(UUID.randomUUID()).userId(customer.getId()).recipientName("Ada")
                .line1("Keizersgracht 1").city("Amsterdam").countryCode("NL").build();
        when(addresses.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(customer.getId()))
                .thenReturn(List.of(address));

        service.erase(customer.getId(), self);

        // Only ever personal, and nothing references them — so there is nothing to keep an
        // anonymised version of.
        verify(addresses).delete(address);
    }

    @Test
    @DisplayName("the other services are told")
    void erasureIsAnnounced() {
        service.erase(customer.getId(), self);

        // Their copies — the name on an order, the byline on a review — are theirs to scrub.
        verify(outboxService).append(anyString(), anyString(), any(UserErasedEvent.class));
    }

    @Test
    @DisplayName("the audit entry is written while the account can still be named")
    void auditHappensBeforeTheIdentityGoes() {
        service.erase(customer.getId(), admin);

        // Recorded before the scrub, so the entry says whose account it was. Afterwards it would
        // name a placeholder, which is useless to an investigation.
        verify(auditService).record(eq(admin), eq("ACCOUNT_ERASED"), eq("USER"),
                eq(customer.getId()),
                org.mockito.ArgumentMatchers.contains("ada@commerceflow.io"));
    }

    @Test
    @DisplayName("erasing twice is not an error")
    void secondErasureIsQuiet() {
        customer.setEmail("erased-" + UUID.randomUUID() + "@erased.invalid");

        service.erase(customer.getId(), self);

        // A retried request, or a customer who asked twice, should get the same answer as the
        // first time rather than a failure about something already done.
        verify(users, never()).save(any());
        verify(outboxService, never()).append(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a customer cannot erase somebody else's account")
    void otherPeoplesAccountsAreInvisible() {
        AuthenticatedUser stranger = new AuthenticatedUser(UUID.randomUUID(), "eve@example.com",
                Set.of("CUSTOMER"), UUID.randomUUID().toString());

        assertThatThrownBy(() -> service.erase(customer.getId(), stranger))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("an administrator can erase anybody's")
    void adminCanEraseAny() {
        service.erase(customer.getId(), admin);

        assertThat(customer.getEmail()).endsWith("@erased.invalid");
    }

    @Test
    @DisplayName("the last administrator who can still sign in is refused")
    void lastAdministratorIsProtected() {
        customer.setRoles(EnumSet.of(Role.ADMIN));
        when(users.countEnabledWithRole(Role.ADMIN)).thenReturn(1L);

        // The same rule guards a role change, and it matters more here: an operator who realises
        // what they have done cannot put it back.
        assertThatThrownBy(() -> service.erase(customer.getId(), admin))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cannot be undone");
    }

    @Test
    @DisplayName("an administrator who is not the last one may still be erased")
    void otherAdministratorsCanGo() {
        customer.setRoles(EnumSet.of(Role.ADMIN));
        when(users.countEnabledWithRole(Role.ADMIN)).thenReturn(3L);

        service.erase(customer.getId(), admin);

        assertThat(customer.getEmail()).endsWith("@erased.invalid");
    }
}
