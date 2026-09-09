package com.commerceflow.authservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.EnumSet;
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

import com.commerceflow.authservice.dto.UpdateUserRequest;
import com.commerceflow.authservice.entity.Role;
import com.commerceflow.authservice.entity.User;
import com.commerceflow.authservice.mapper.UserMapper;
import com.commerceflow.authservice.repository.RefreshTokenRepository;
import com.commerceflow.authservice.repository.UserRepository;
import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.security.AuthenticatedUser;

/**
 * Most of this file is about the two changes an administrator is <em>not</em> allowed to make.
 *
 * <p>Neither is prevented by types or by permissions — an administrator genuinely has the right to
 * disable accounts and change roles. What makes them special is the recovery: both leave a
 * platform nobody can administer, and the only way back is a database console. So they are
 * refused, and these tests are why they stay refused.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserAdminServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private AuditService auditService;

    @Mock
    private LoginThrottle loginThrottle;

    private UserAdminService service;
    private User target;
    private AuthenticatedUser caller;

    @BeforeEach
    void setUp() {
        service = new UserAdminService(userRepository, refreshTokenRepository, userMapper, auditService, loginThrottle);

        target = User.builder()
                .id(UUID.randomUUID())
                .email("liam@commerceflow.io")
                .passwordHash("$2a$10$hash")
                .fullName("Liam Nguyen")
                .enabled(true)
                .emailVerified(true)
                .roles(EnumSet.of(Role.CUSTOMER))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        caller = new AuthenticatedUser(UUID.randomUUID(), "admin@commerceflow.io",
                Set.of("ADMIN"), UUID.randomUUID().toString());

        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(userRepository.countEnabledWithRole(Role.ADMIN)).thenReturn(3L);
    }

    @Test
    @DisplayName("disabling an account signs it out everywhere, not just eventually")
    void disablingRevokesSessions() {
        service.update(target.getId(), new UpdateUserRequest(false, null), caller);

        assertThat(target.isEnabled()).isFalse();
        // Without this the person you just locked out stays signed in until their refresh token
        // expires a week later, which is not what anybody means by "disable".
        verify(refreshTokenRepository)
                .revokeAllForUser(org.mockito.ArgumentMatchers.eq(target.getId()),
                        any(Instant.class));
    }

    @Test
    @DisplayName("re-enabling does not revoke anything")
    void reEnablingLeavesSessionsAlone() {
        target.setEnabled(false);

        service.update(target.getId(), new UpdateUserRequest(true, null), caller);

        assertThat(target.isEnabled()).isTrue();
        verify(refreshTokenRepository, never()).revokeAllForUser(any(UUID.class), any(Instant.class));
    }

    @Test
    @DisplayName("an omitted field is left alone, which is what makes this a PATCH")
    void omittedFieldsAreUntouched() {
        service.update(target.getId(), new UpdateUserRequest(null, Set.of("ADMIN")), caller);

        assertThat(target.getRoles()).containsExactly(Role.ADMIN);
        // Roles changed; enabled was not mentioned and must not have moved.
        assertThat(target.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("roles are replaced wholesale, and parsed case-insensitively")
    void rolesAreReplaced() {
        service.update(target.getId(), new UpdateUserRequest(null, Set.of("admin", "customer")),
                caller);

        assertThat(target.getRoles()).containsExactlyInAnyOrder(Role.ADMIN, Role.CUSTOMER);
    }

    @Test
    @DisplayName("an unknown role is rejected rather than silently dropped")
    void unknownRoleIsRejected() {
        // Silently ignoring it would leave an operator convinced they granted something.
        assertThatThrownBy(() -> service.update(target.getId(),
                new UpdateUserRequest(null, Set.of("SUPERUSER")), caller))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unknown role");
    }

    @Test
    @DisplayName("an administrator cannot disable their own account")
    void cannotDisableSelf() {
        User self = selfAsAdmin();

        assertThatThrownBy(() -> service.update(self.getId(),
                new UpdateUserRequest(false, null), caller))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("your own account");

        assertThat(self.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("an administrator cannot remove their own administrator role")
    void cannotDemoteSelf() {
        User self = selfAsAdmin();

        assertThatThrownBy(() -> service.update(self.getId(),
                new UpdateUserRequest(null, Set.of("CUSTOMER")), caller))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("your own administrator role");

        assertThat(self.getRoles()).contains(Role.ADMIN);
    }

    @Test
    @DisplayName("the last enabled administrator cannot be disabled")
    void cannotDisableLastAdministrator() {
        target.setRoles(EnumSet.of(Role.ADMIN));
        when(userRepository.countEnabledWithRole(Role.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.update(target.getId(),
                new UpdateUserRequest(false, null), caller))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("last enabled administrator");
    }

    @Test
    @DisplayName("the last enabled administrator cannot be demoted either")
    void cannotDemoteLastAdministrator() {
        target.setRoles(EnumSet.of(Role.ADMIN));
        when(userRepository.countEnabledWithRole(Role.ADMIN)).thenReturn(1L);

        // Demotion and disabling leave the same hole, so they get the same guard.
        assertThatThrownBy(() -> service.update(target.getId(),
                new UpdateUserRequest(null, Set.of("CUSTOMER")), caller))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("last enabled administrator");
    }

    @Test
    @DisplayName("one of several administrators can be disabled")
    void anotherAdministratorCanBeDisabled() {
        target.setRoles(EnumSet.of(Role.ADMIN));
        when(userRepository.countEnabledWithRole(Role.ADMIN)).thenReturn(2L);

        service.update(target.getId(), new UpdateUserRequest(false, null), caller);

        assertThat(target.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("a disabled administrator does not count towards the last-administrator rule")
    void disabledAdministratorsDoNotCount() {
        // countEnabledWithRole is the query, not countWithRole. A disabled administrator is no
        // more able to help than none at all, so counting them would let the platform reach a
        // state with nobody who can sign in and administer it.
        target.setRoles(EnumSet.of(Role.ADMIN));
        target.setEnabled(false);

        service.update(target.getId(), new UpdateUserRequest(null, Set.of("CUSTOMER")), caller);

        assertThat(target.getRoles()).containsExactly(Role.CUSTOMER);
    }

    private User selfAsAdmin() {
        User self = User.builder()
                .id(caller.userId())
                .email(caller.email())
                .passwordHash("$2a$10$hash")
                .fullName("The Administrator")
                .enabled(true)
                .emailVerified(true)
                .roles(EnumSet.of(Role.ADMIN, Role.CUSTOMER))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(userRepository.findById(self.getId())).thenReturn(Optional.of(self));
        return self;
    }
}
