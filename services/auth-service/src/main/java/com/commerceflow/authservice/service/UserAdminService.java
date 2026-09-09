package com.commerceflow.authservice.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.authservice.dto.UpdateUserRequest;
import com.commerceflow.authservice.dto.UserResponse;
import com.commerceflow.authservice.entity.Role;
import com.commerceflow.authservice.entity.User;
import com.commerceflow.authservice.mapper.UserMapper;
import com.commerceflow.authservice.repository.RefreshTokenRepository;
import com.commerceflow.authservice.repository.UserRepository;
import com.commerceflow.common.dto.PageResponse;
import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.common.security.AuthenticatedUser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Back-office account administration.
 *
 * <p>Separate from {@link AuthService}, which is about a person proving who they are. This is
 * about somebody else changing what that person can do, and the two have little in common beyond
 * the table they read.
 *
 * <h2>Two things an administrator is not allowed to do</h2>
 *
 * <p><b>Lock themselves out.</b> Disabling your own account, or removing your own administrator
 * role, is refused. Not because an operator may never want to step down, but because the recovery
 * is a database console at three in the morning — and the mistake is one row away from the button
 * that disables somebody else.
 *
 * <p><b>Remove the last administrator.</b> An organisation with no enabled administrator cannot
 * appoint one, and the way out is that same console. The check counts <em>enabled</em>
 * administrators, because a disabled one is no more able to help than none at all.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private static final List<String> SORTABLE_FIELDS =
            List.of("createdAt", "email", "fullName", "enabled");

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserMapper userMapper;
    private final AuditService auditService;
    private final LoginThrottle loginThrottle;

    /** Paged account list. Every filter is optional. */
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(String search, String role, Boolean enabled,
                                             int page, int size, String sortBy, String direction) {
        Page<User> result = userRepository.search(
                blankToNull(search), parseRole(role), enabled,
                PageRequest.of(page, size, sort(sortBy, direction)));

        List<UserResponse> content = result.getContent().stream()
                .map(userMapper::toResponse)
                .toList();
        return PageResponse.of(content, page, size, result.getTotalElements());
    }

    /** @throws ResourceNotFoundException when there is no such account */
    @Transactional(readOnly = true)
    public UserResponse getById(UUID userId) {
        return userRepository.findById(userId)
                .map(userMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Account not found: " + userId));
    }

    /**
     * Changes what an account is and can do.
     *
     * <p>Disabling revokes every refresh token the account holds. Without that, someone you have
     * just locked out stays signed in until their refresh token expires a week later, which is
     * not what anybody means by "disable".
     *
     * @throws BusinessException when the change would lock the caller out, or remove the last
     *     enabled administrator
     */
    @Transactional
    public UserResponse update(UUID userId, UpdateUserRequest request, AuthenticatedUser caller) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Account not found: " + userId));

        boolean self = user.getId().equals(caller.userId());

        if (Boolean.FALSE.equals(request.enabled())) {
            requireNotSelf(self, "You cannot disable your own account");
            requireNotLastAdministrator(user, "Disabling");
        }

        Set<Role> roles = request.roles() == null ? null : parseRoles(request.roles());
        boolean losesAdmin = roles != null
                && !roles.contains(Role.ADMIN)
                && user.getRoles().contains(Role.ADMIN);

        if (losesAdmin) {
            requireNotSelf(self, "You cannot remove your own administrator role");
            requireNotLastAdministrator(user, "Removing the administrator role from");
        }

        if (request.enabled() != null && request.enabled() != user.isEnabled()) {
            user.setEnabled(request.enabled());
            if (!request.enabled()) {
                int revoked = refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now());
                log.warn("Account {} disabled by {}; {} session(s) revoked", user.getId(),
                        caller.userId(), revoked);
            } else {
                log.info("Account {} re-enabled by {}", user.getId(), caller.userId());
            }
        }

        if (roles != null && !roles.equals(user.getRoles())) {
            // Logged at warn on purpose: a change of what somebody is allowed to do is the kind
            // of event an audit asks about months later.
            log.warn("Account {} roles changed from {} to {} by {}", user.getId(),
                    user.getRoles(), roles, caller.userId());
            // Roles are the highest-value change in the platform: they decide who may do
            // everything else on this list.
            auditService.record(caller, "ROLES_CHANGED", "USER", user.getId(),
                    "Roles changed from " + user.getRoles() + " to " + roles);
            user.setRoles(EnumSet.copyOf(roles));
        }

        user.setUpdatedAt(Instant.now());
        return userMapper.toResponse(user);
    }

    // =====================================================================================
    // Guards
    // =====================================================================================

    private static void requireNotSelf(boolean self, String message) {
        if (self) {
            throw new BusinessException(ErrorCode.FORBIDDEN, message
                    + ". Ask another administrator, so there is always someone who can undo it.");
        }
    }

    private void requireNotLastAdministrator(User user, String action) {
        if (!user.getRoles().contains(Role.ADMIN) || !user.isEnabled()) {
            return;
        }
        if (userRepository.countEnabledWithRole(Role.ADMIN) <= 1) {
            throw new BusinessException(ErrorCode.CONFLICT, action
                    + " the last enabled administrator would leave nobody able to administer the "
                    + "platform, and nobody able to undo it.");
        }
    }

    // =====================================================================================
    // Parsing
    // =====================================================================================

    private static Set<Role> parseRoles(Set<String> raw) {
        Set<Role> roles = raw.stream()
                .map(UserAdminService::parseRoleOrFail)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Role.class)));

        if (roles.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "An account must have at least one role");
        }
        return roles;
    }

    private static Role parseRoleOrFail(String value) {
        try {
            return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Unknown role: " + value);
        }
    }

    /** Null for an absent filter; an unknown value is a client error, not an empty list. */
    private static Role parseRole(String value) {
        return blankToNull(value) == null ? null : parseRoleOrFail(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Sort sort(String sortBy, String direction) {
        String field = SORTABLE_FIELDS.contains(sortBy) ? sortBy : "createdAt";
        Sort.Direction dir =
                "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(dir, field);
    }

    /**
     * Releases a sign-in lock by hand.
     *
     * <p>Audited, because "who let this account back in" is a fair question after an account is
     * compromised — and unlocking somebody else's sign-in is precisely the kind of favour an
     * attacker with an administrator account would ask for.
     */
    @Transactional(readOnly = true)
    public void unlockSignIn(UUID userId, AuthenticatedUser caller) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Account not found: " + userId));

        loginThrottle.unlock(user.getEmail());
        auditService.record(caller, "SIGNIN_UNLOCKED", "USER", userId,
                "Released the sign-in lock");
    }
}
