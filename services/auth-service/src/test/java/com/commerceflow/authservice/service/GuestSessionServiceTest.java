package com.commerceflow.authservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.commerceflow.authservice.dto.GuestSessionRequest;
import com.commerceflow.authservice.entity.Role;
import com.commerceflow.authservice.entity.User;
import com.commerceflow.authservice.repository.UserRepository;
import com.commerceflow.common.exception.ConflictException;

/**
 * Guest checkout.
 *
 * <p>One test here matters more than the others: an address that already has an account must be
 * refused. This endpoint hands out a session in exchange for an email address, so reusing an
 * existing account would mean anybody could type your address and be signed in as you.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GuestSessionServiceTest {

    @Mock
    private UserRepository users;

    @Mock
    private AuthService authService;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    private GuestSessionService service;

    @BeforeEach
    void setUp() {
        service = new GuestSessionService(users, new BCryptPasswordEncoder(4), authService);
        when(users.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("an address that already has an account is refused")
    void existingAccountIsRefused() {
        when(users.existsByEmailIgnoreCase("ada@example.com")).thenReturn(true);

        // The whole security model of this endpoint. Without it, "guest checkout" is a login
        // form that accepts an email address and no password.
        assertThatThrownBy(() ->
                service.start(new GuestSessionRequest("ada@example.com", "Ada")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Please sign in");
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("a guest account is a real account, flagged")
    void guestAccountIsReal() {
        service.start(new GuestSessionRequest("new@example.com", "Ada Lovelace"));

        verify(users).save(userCaptor.capture());
        User guest = userCaptor.getValue();

        // Real, so nothing downstream needs a second code path for a customer who might not be
        // one. Flagged, so an operator can still tell a checkout from a sign-up.
        assertThat(guest.isGuest()).isTrue();
        assertThat(guest.isEnabled()).isTrue();
        assertThat(guest.getRoles()).containsExactly(Role.CUSTOMER);
        assertThat(guest.isEmailVerified()).isFalse();
    }

    @Test
    @DisplayName("the password hash is real and unguessable, not blank")
    void passwordHashIsUnusableRatherThanEmpty() {
        service.start(new GuestSessionRequest("new@example.com", null));

        verify(users).save(userCaptor.capture());
        String hash = userCaptor.getValue().getPasswordHash();

        // A random hash rather than null or "". Password comparison then simply fails, instead
        // of depending on every future code path remembering to check the guest flag first.
        assertThat(hash).isNotBlank().startsWith("$2");
        assertThat(new BCryptPasswordEncoder(4).matches("", hash)).isFalse();
    }

    @Test
    @DisplayName("the address is stored lower case, like every other account")
    void emailIsNormalised() {
        service.start(new GuestSessionRequest("  Ada@Example.COM ", null));

        verify(users).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("ada@example.com");
    }

    @Test
    @DisplayName("no name falls back to the address, not to the word Guest")
    void missingNameFallsBackUsefully() {
        service.start(new GuestSessionRequest("ada@example.com", null));

        verify(users).save(userCaptor.capture());

        // A parcel addressed to "Guest" is a parcel a courier cannot deliver.
        assertThat(userCaptor.getValue().getFullName()).isEqualTo("ada");
    }

    @Test
    @DisplayName("the session comes from the same path a normal login uses")
    void tokensComeFromTheOnePlace() {
        service.start(new GuestSessionRequest("new@example.com", "Ada"));

        // A second token-minting routine is a second place for the claims, the expiry and the
        // refresh bookkeeping to drift apart.
        verify(authService).issueTokens(any(User.class));
    }
}
