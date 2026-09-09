package com.commerceflow.authservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.commerceflow.authservice.dto.AuthResponse;
import com.commerceflow.authservice.dto.LoginRequest;
import com.commerceflow.authservice.dto.RefreshTokenRequest;
import com.commerceflow.authservice.dto.RegisterRequest;
import com.commerceflow.authservice.dto.UserResponse;
import com.commerceflow.authservice.entity.RefreshToken;
import com.commerceflow.authservice.config.AuthProperties;
import com.commerceflow.authservice.entity.Role;
import com.commerceflow.authservice.entity.User;
import com.commerceflow.authservice.mapper.UserMapper;
import com.commerceflow.authservice.repository.RefreshTokenRepository;
import com.commerceflow.authservice.repository.UserRepository;
import com.commerceflow.common.event.NotificationSendEvent;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.UnauthorizedException;
import com.commerceflow.common.security.JwtProperties;
import com.commerceflow.common.security.JwtTokenProvider;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    private static final String SECRET = "commerceflow-unit-test-secret-key-of-at-least-32-bytes";
    private static final String EMAIL = "ada@commerceflow.io";
    private static final String RAW_PASSWORD = "S3cret-pass";

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenBlacklistService blacklistService;

    /** Registration now delegates the verification email; AuthService no longer writes events. */
    @Mock
    private AccountRecoveryService recoveryService;

    @Mock
    private UserMapper userMapper;

    @Mock
    private LoginThrottle loginThrottle;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    private JwtTokenProvider tokenProvider;
    private JwtProperties jwtProperties;
    private AuthService authService;
    private AuthProperties authProperties;
    private User existingUser;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret(SECRET);
        jwtProperties.setIssuer("commerceflow");
        jwtProperties.setAccessTokenTtl(Duration.ofMinutes(15));
        jwtProperties.setRefreshTokenTtl(Duration.ofDays(7));
        tokenProvider = new JwtTokenProvider(jwtProperties);

        authProperties = new AuthProperties();
        authService = new AuthService(userRepository, refreshTokenRepository, passwordEncoder,
                loginThrottle, tokenProvider, jwtProperties, blacklistService, recoveryService,
                authProperties, userMapper);

        existingUser = User.builder()
                .id(UUID.randomUUID())
                .email(EMAIL)
                .passwordHash("$2a$10$hashed")
                .fullName("Ada Lovelace")
                .enabled(true)
                .roles(EnumSet.of(Role.CUSTOMER))
                .build();

        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(userMapper.toResponse(any(User.class))).thenAnswer(call -> {
            User user = call.getArgument(0);
            return new UserResponse(user.getId(), user.getEmail(), user.getFullName(),
                    user.getPhone(), user.roleNames(), user.isEnabled(), Instant.now());
        });
    }

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("stores a lower-cased email, a hashed password and the CUSTOMER role")
        void createsCustomerAccount() {
            when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(false);
            when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn("$2a$10$encoded");

            UserResponse response = authService.register(new RegisterRequest(
                    "  Ada@CommerceFlow.IO  ", RAW_PASSWORD, " Ada Lovelace ", null));

            verify(userRepository).save(userCaptor.capture());
            User saved = userCaptor.getValue();

            assertThat(saved.getEmail()).isEqualTo(EMAIL);
            assertThat(saved.getPasswordHash()).isEqualTo("$2a$10$encoded");
            assertThat(saved.getFullName()).isEqualTo("Ada Lovelace");
            assertThat(saved.getRoles()).containsExactly(Role.CUSTOMER);
            assertThat(saved.isEnabled()).isTrue();
            assertThat(response.email()).isEqualTo(EMAIL);
        }

        @Test
        @DisplayName("queues a welcome notification in the same transaction")
        void queuesVerificationEmail() {
            when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$encoded");

            authService.register(new RegisterRequest(EMAIL, RAW_PASSWORD, "Ada Lovelace", null));

            // One email on sign-up, and it is the verification link. The welcome now waits until
            // the address has been proven, so it only ever reaches a real mailbox.
            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(recoveryService).sendVerificationLink(userCaptor.capture());
            assertThat(userCaptor.getValue().getEmail()).isEqualTo(EMAIL);
            assertThat(userCaptor.getValue().isEmailVerified()).isFalse();
        }

        @Test
        @DisplayName("rejects an address that is already registered")
        void rejectsDuplicateEmail() {
            when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(true);

            assertThatThrownBy(() -> authService.register(
                    new RegisterRequest(EMAIL, RAW_PASSWORD, "Ada Lovelace", null)))
                    .isInstanceOf(ConflictException.class)
                    .extracting(ex -> ((ConflictException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED);

            verify(userRepository, never()).save(any(User.class));
            verify(recoveryService, never()).sendVerificationLink(any(User.class));
        }
    }

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("issues a usable access and refresh token pair")
        void issuesTokenPair() {
            when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(existingUser));
            when(passwordEncoder.matches(RAW_PASSWORD, existingUser.getPasswordHash()))
                    .thenReturn(true);

            AuthResponse response =
                    authService.login(new LoginRequest(EMAIL, RAW_PASSWORD), "JUnit", "127.0.0.1");

            assertThat(response.tokenType()).isEqualTo("Bearer");
            assertThat(response.expiresIn()).isEqualTo(900L);
            assertThat(tokenProvider.authenticate(response.accessToken()).userId())
                    .isEqualTo(existingUser.getId());
            assertThat(tokenProvider.isValid(response.refreshToken(),
                    com.commerceflow.common.security.TokenType.REFRESH)).isTrue();

            verify(refreshTokenRepository).save(any(RefreshToken.class));
        }

        @Test
        @DisplayName("an unknown address and a wrong password are indistinguishable")
        void doesNotLeakAccountExistence() {
            when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> authService.login(
                    new LoginRequest(EMAIL, RAW_PASSWORD), null, null))
                    .isInstanceOf(UnauthorizedException.class)
                    .extracting(ex -> ((UnauthorizedException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

            when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(existingUser));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
            assertThatThrownBy(() -> authService.login(
                    new LoginRequest(EMAIL, "wrong-password"), null, null))
                    .isInstanceOf(UnauthorizedException.class)
                    .extracting(ex -> ((UnauthorizedException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }

        @Test
        @DisplayName("a disabled account cannot log in")
        void rejectsDisabledAccount() {
            existingUser.setEnabled(false);
            when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(existingUser));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

            assertThatThrownBy(() -> authService.login(
                    new LoginRequest(EMAIL, RAW_PASSWORD), null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_DISABLED);
        }
    }

    @Nested
    @DisplayName("refresh")
    class Refresh {

        @Test
        @DisplayName("rotates the token pair and revokes the presented token")
        void rotatesTokenPair() {
            UUID tokenId = UUID.randomUUID();
            String refreshToken = tokenProvider.generateRefreshToken(
                    existingUser.getId(), EMAIL, tokenId.toString());

            when(refreshTokenRepository.findById(tokenId)).thenReturn(Optional.of(
                    storedToken(tokenId, refreshToken, false, Instant.now().plusSeconds(3600))));
            when(userRepository.findById(existingUser.getId())).thenReturn(Optional.of(existingUser));

            AuthResponse response = authService.refresh(
                    new RefreshTokenRequest(refreshToken), "JUnit", "127.0.0.1");

            assertThat(response.refreshToken()).isNotEqualTo(refreshToken);

            ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokenRepository, org.mockito.Mockito.atLeastOnce())
                    .save(tokenCaptor.capture());
            assertThat(tokenCaptor.getAllValues())
                    .anyMatch(token -> token.getId().equals(tokenId) && token.isRevoked());
        }

        @Test
        @DisplayName("reusing an already rotated token revokes every session of the account")
        void reuseRevokesAllSessions() {
            UUID tokenId = UUID.randomUUID();
            String refreshToken = tokenProvider.generateRefreshToken(
                    existingUser.getId(), EMAIL, tokenId.toString());

            when(refreshTokenRepository.findById(tokenId)).thenReturn(Optional.of(
                    storedToken(tokenId, refreshToken, true, Instant.now().plusSeconds(3600))));

            assertThatThrownBy(() -> authService.refresh(
                    new RefreshTokenRequest(refreshToken), null, null))
                    .isInstanceOf(UnauthorizedException.class);

            verify(refreshTokenRepository).revokeAllForUser(eq(existingUser.getId()), any());
        }

        @Test
        @DisplayName("an access token is not accepted where a refresh token is required")
        void rejectsAccessTokenAsRefreshToken() {
            String accessToken = tokenProvider.generateAccessToken(
                    existingUser.getId(), EMAIL, Set.of(Role.CUSTOMER.name()));

            assertThatThrownBy(() -> authService.refresh(
                    new RefreshTokenRequest(accessToken), null, null))
                    .isInstanceOf(UnauthorizedException.class);
        }

        @Test
        @DisplayName("a token whose stored hash does not match is rejected")
        void rejectsTamperedToken() {
            UUID tokenId = UUID.randomUUID();
            String refreshToken = tokenProvider.generateRefreshToken(
                    existingUser.getId(), EMAIL, tokenId.toString());

            RefreshToken stored = storedToken(tokenId, "a-different-token", false,
                    Instant.now().plusSeconds(3600));
            when(refreshTokenRepository.findById(tokenId)).thenReturn(Optional.of(stored));

            assertThatThrownBy(() -> authService.refresh(
                    new RefreshTokenRequest(refreshToken), null, null))
                    .isInstanceOf(UnauthorizedException.class)
                    .extracting(ex -> ((UnauthorizedException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);
        }
    }

    @Test
    @DisplayName("logout denies the access token and revokes every refresh token")
    void logoutRevokesEverything() {
        String accessToken = tokenProvider.generateAccessToken(
                existingUser.getId(), EMAIL, Set.of(Role.CUSTOMER.name()));

        authService.logout(accessToken);

        verify(blacklistService).revoke(anyString(), any(Instant.class));
        verify(refreshTokenRepository).revokeAllForUser(eq(existingUser.getId()), any(Instant.class));
    }

    private RefreshToken storedToken(UUID id, String tokenValue, boolean revoked, Instant expiresAt) {
        return RefreshToken.builder()
                .id(id)
                .userId(existingUser.getId())
                .tokenHash(AuthService.hash(tokenValue))
                .expiresAt(expiresAt)
                .revoked(revoked)
                .createdAt(Instant.now())
                .build();
    }
}
