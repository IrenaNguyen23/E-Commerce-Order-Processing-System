package com.commerceflow.authservice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.authservice.dto.AuthResponse;
import com.commerceflow.authservice.dto.LoginRequest;
import com.commerceflow.authservice.dto.RefreshTokenRequest;
import com.commerceflow.authservice.dto.RegisterRequest;
import com.commerceflow.authservice.dto.UserResponse;
import com.commerceflow.authservice.entity.RefreshToken;
import com.commerceflow.authservice.entity.Role;
import com.commerceflow.authservice.entity.User;
import com.commerceflow.authservice.mapper.UserMapper;
import com.commerceflow.authservice.repository.RefreshTokenRepository;
import com.commerceflow.authservice.repository.UserRepository;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.common.exception.UnauthorizedException;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.common.security.JwtProperties;
import com.commerceflow.common.security.JwtTokenProvider;
import com.commerceflow.common.security.TokenType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Registration, login, refresh-token rotation and logout.
 *
 * <p>Login failures are deliberately indistinguishable from one another: an unknown address and a
 * wrong password both return {@code INVALID_CREDENTIALS}, so the endpoint cannot be used to
 * enumerate registered customers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {


    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginThrottle loginThrottle;
    private final JwtTokenProvider tokenProvider;
    private final JwtProperties jwtProperties;
    private final TokenBlacklistService blacklistService;
    private final AccountRecoveryService recoveryService;
    private final com.commerceflow.authservice.config.AuthProperties properties;
    private final UserMapper userMapper;

    /**
     * Creates an account and queues a welcome notification in the same transaction.
     *
     * @throws ConflictException when the address is already registered
     */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalise(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED,
                    "An account already exists for " + email);
        }

        User user = User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName().trim())
                .phone(request.phone())
                .enabled(true)
                .roles(EnumSet.of(Role.CUSTOMER))
                .build();

        User saved = userRepository.save(user);

        // One email, not two. The welcome goes out once the address is proven, so it only ever
        // reaches a mailbox that exists — and a new customer gets a single, unambiguous next step.
        recoveryService.sendVerificationLink(saved);

        log.info("Registered account {}", saved.getId());
        return userMapper.toResponse(saved);
    }

    /**
     * Verifies credentials and issues a token pair.
     *
     * @throws UnauthorizedException when the credentials do not match
     * @throws BusinessException     when the account is disabled
     */
    @Transactional
    public AuthResponse login(LoginRequest request, String userAgent, String ipAddress) {
        String email = normalise(request.email());

        // Checked before the account is even looked up, and phrased without confirming that the
        // address exists. A lock message on an unregistered address would be a way to enumerate
        // accounts — ask about ten thousand addresses, see which ones can be locked.
        if (loginThrottle.isLocked(email)) {
            long minutes = Math.max(1, loginThrottle.remainingLock(email).toMinutes());
            throw new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS,
                    "Too many sign-in attempts. Try again in about " + minutes
                            + " minute" + (minutes == 1 ? "" : "s") + ".");
        }

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> {
                    // Counted even though no such account exists. Otherwise the throttle itself
                    // answers "is this address registered": an address that can never be locked
                    // is an address with no account behind it.
                    loginThrottle.recordFailure(email);
                    return new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS);
                });

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            boolean locked = loginThrottle.recordFailure(email);
            log.warn("Failed login attempt for account {}{}", user.getId(),
                    locked ? " (now locked)" : "");
            throw new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!user.isEnabled()) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        // Checked after the password, on purpose. Answering "confirm your email" to a wrong
        // password would tell a stranger that the address is registered.
        if (properties.isRequireVerifiedEmail() && !user.isEmailVerified()) {
            log.info("Login refused for account {}: address not verified", user.getId());
            throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED,
                    "Confirm your email address before signing in. "
                            + "We can send you another link if you need one.");
        }

        // Cleared only once every other check has passed. Clearing it on a correct password
        // alone would let somebody with valid credentials for a disabled account reset the
        // counter at will.
        loginThrottle.recordSuccess(email);

        log.info("Successful login for account {}", user.getId());
        return issueTokenPair(user, userAgent, ipAddress);
    }

    /**
     * Rotates a refresh token.
     *
     * <p>Presenting a token that was already rotated is treated as a compromise indicator: every
     * live session of that account is revoked.
     *
     * @throws UnauthorizedException when the token is unusable
     */
    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request, String userAgent, String ipAddress) {
        AuthenticatedUser principal =
                tokenProvider.authenticate(request.refreshToken(), TokenType.REFRESH);
        UUID tokenId = UUID.fromString(principal.tokenId());

        RefreshToken stored = refreshTokenRepository.findById(tokenId)
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.REFRESH_TOKEN_INVALID));

        if (!stored.getTokenHash().equals(hash(request.refreshToken()))) {
            throw new UnauthorizedException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        if (stored.isRevoked()) {
            log.warn("Refresh token reuse detected for account {}; revoking all sessions",
                    stored.getUserId());
            refreshTokenRepository.revokeAllForUser(stored.getUserId(), Instant.now());
            throw new UnauthorizedException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        if (stored.isExpired()) {
            throw new UnauthorizedException(ErrorCode.TOKEN_EXPIRED, "Refresh token has expired");
        }

        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.REFRESH_TOKEN_INVALID));
        if (!user.isEnabled()) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }

        IssuedTokens issued = issueTokens(user, userAgent, ipAddress);
        stored.revoke(issued.refreshTokenId());
        refreshTokenRepository.save(stored);
        return issued.response();
    }

    /**
     * Ends the session: the presented access token is denied for the rest of its life and every
     * refresh token of the account is revoked.
     */
    @Transactional
    public void logout(String accessToken) {
        AuthenticatedUser principal = tokenProvider.authenticate(accessToken, TokenType.ACCESS);
        blacklistService.revoke(principal.tokenId(), tokenProvider.expiryOf(accessToken));
        int revoked = refreshTokenRepository.revokeAllForUser(principal.userId(), Instant.now());
        log.info("Logged out account {} and revoked {} refresh token(s)", principal.userId(), revoked);
    }

    /** @throws ResourceNotFoundException when the account no longer exists */
    @Transactional(readOnly = true)
    public UserResponse currentUser(UUID userId) {
        return userRepository.findById(userId)
                .map(userMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    /**
     * Issues a session for an account that has just been created without a password.
     *
     * <p>Package-visible rather than private so guest checkout can reuse exactly this path. A
     * second token-minting routine is a second place for the claims, the expiry and the refresh
     * bookkeeping to drift apart.
     */
    AuthResponse issueTokens(User user) {
        return issueTokenPair(user, null, null);
    }

    private AuthResponse issueTokenPair(User user, String userAgent, String ipAddress) {
        return issueTokens(user, userAgent, ipAddress).response();
    }

    private IssuedTokens issueTokens(User user, String userAgent, String ipAddress) {
        String accessToken = tokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), user.roleNames());

        UUID refreshTokenId = UUID.randomUUID();
        String refreshToken = tokenProvider.generateRefreshToken(
                user.getId(), user.getEmail(), refreshTokenId.toString());

        refreshTokenRepository.save(RefreshToken.builder()
                .id(refreshTokenId)
                .userId(user.getId())
                .tokenHash(hash(refreshToken))
                .expiresAt(Instant.now().plus(jwtProperties.getRefreshTokenTtl()))
                .revoked(false)
                .createdAt(Instant.now())
                .userAgent(truncate(userAgent, 255))
                .ipAddress(truncate(ipAddress, 64))
                .build());

        AuthResponse response = AuthResponse.of(accessToken, refreshToken,
                jwtProperties.getAccessTokenTtl().toSeconds(), userMapper.toResponse(user));
        return new IssuedTokens(response, refreshTokenId);
    }

    private record IssuedTokens(AuthResponse response, UUID refreshTokenId) {
    }

    private static String normalise(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** SHA-256 of the token value; only the digest ever reaches the database. */
    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JDK specification", ex);
        }
    }
}
