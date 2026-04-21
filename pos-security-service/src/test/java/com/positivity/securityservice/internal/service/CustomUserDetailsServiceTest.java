package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Unit tests for {@link CustomUserDetailsService#loadUserByUsername(String)}.
 *
 * <p>
 * Verifies that account-state flags from the {@code User} entity are correctly
 * propagated through the 7-arg Spring Security {@code User} constructor to the
 * returned {@code SecurityUserPrincipal} (via delegate).
 *
 * <p>
 * T10: entity defaults (enabled=true, etc.) are correct at construction time.
 * T11-T14: false entity flags produce false principal account-state flags.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomUserDetailsServiceTest — AUTH-002")
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleAssignmentRepository roleAssignmentRepository;

    @InjectMocks
    private CustomUserDetailsService sut;

    // =========================================================
    // T10 — Baseline: new User() has correct default field values
    // Expected: PASS (entity field declarations set defaults)
    // =========================================================

    @Test
    @DisplayName("T10: new User() has correct default account-state values")
    void t10_newUserEntity_hasExpectedAccountStateDefaults() {
        User user = new User();

        assertThat(user.isEnabled()).as("enabled defaults to true").isTrue();
        assertThat(user.isAccountNonLocked())
                .as("accountNonLocked defaults to true")
                .isTrue();
        assertThat(user.isAccountNonExpired())
                .as("accountNonExpired defaults to true")
                .isTrue();
        assertThat(user.isCredentialsNonExpired())
                .as("credentialsNonExpired defaults to true")
                .isTrue();
        assertThat(user.getFailedLoginAttempts())
                .as("failedLoginAttempts defaults to 0")
                .isZero();
    }

    // =========================================================
    // T11 — loadUserByUsername must propagate enabled=false
    // Expected: FAIL (RED) — 3-arg constructor defaults enabled=true
    // =========================================================

    @Nested
    @DisplayName("T11: loadUserByUsername maps enabled=false from entity to principal")
    class EnabledFlagMapping {

        @Test
        @DisplayName("T11: principal.isEnabled() == false when entity.enabled == false")
        void t11_loadUserByUsername_mapsEnabledFalseFromEntity() {
            User entity = entityWithDefaults("disableduser");
            entity.setEnabled(false);
            when(userRepository.findByUsername("disableduser")).thenReturn(Optional.of(entity));

            UserDetails principal = sut.loadUserByUsername("disableduser");

            // 7-arg constructor propagates entity flag to principal via delegate
            assertThat(principal.isEnabled())
                    .as("principal.isEnabled() must reflect entity.enabled == false; "
                            + "fails RED because 3-arg constructor defaults to true")
                    .isFalse();
        }
    }

    // =========================================================
    // T12 — loadUserByUsername must propagate accountNonLocked=false
    // Expected: FAIL (RED) — 3-arg constructor defaults accountNonLocked=true
    // =========================================================

    @Nested
    @DisplayName("T12: loadUserByUsername maps accountNonLocked=false from entity to principal")
    class AccountNonLockedFlagMapping {

        @Test
        @DisplayName("T12: principal.isAccountNonLocked() == false when entity.accountNonLocked == false")
        void t12_loadUserByUsername_mapsAccountNonLockedFalseFromEntity() {
            User entity = entityWithDefaults("lockeduser");
            entity.setAccountNonLocked(false);
            when(userRepository.findByUsername("lockeduser")).thenReturn(Optional.of(entity));

            UserDetails principal = sut.loadUserByUsername("lockeduser");

            // 7-arg constructor propagates entity flag to principal via delegate
            assertThat(principal.isAccountNonLocked())
                    .as("principal.isAccountNonLocked() must reflect entity.accountNonLocked == false; "
                            + "fails RED because 3-arg constructor defaults to true")
                    .isFalse();
        }
    }

    // =========================================================
    // T13 — loadUserByUsername must propagate accountNonExpired=false
    // Expected: FAIL (RED) — 3-arg constructor defaults accountNonExpired=true
    // =========================================================

    @Nested
    @DisplayName("T13: loadUserByUsername maps accountNonExpired=false from entity to principal")
    class AccountNonExpiredFlagMapping {

        @Test
        @DisplayName("T13: principal.isAccountNonExpired() == false when entity.accountNonExpired == false")
        void t13_loadUserByUsername_mapsAccountNonExpiredFalseFromEntity() {
            User entity = entityWithDefaults("expiredacctuser");
            entity.setAccountNonExpired(false);
            when(userRepository.findByUsername("expiredacctuser")).thenReturn(Optional.of(entity));

            UserDetails principal = sut.loadUserByUsername("expiredacctuser");

            // 7-arg constructor propagates entity flag to principal via delegate
            assertThat(principal.isAccountNonExpired())
                    .as("principal.isAccountNonExpired() must reflect entity.accountNonExpired == false; "
                            + "fails RED because 3-arg constructor defaults to true")
                    .isFalse();
        }
    }

    // =========================================================
    // T14 — loadUserByUsername must propagate credentialsNonExpired=false
    // Expected: FAIL (RED) — 3-arg constructor defaults credentialsNonExpired=true
    // =========================================================

    @Nested
    @DisplayName("T14: loadUserByUsername maps credentialsNonExpired=false from entity to principal")
    class CredentialsNonExpiredFlagMapping {

        @Test
        @DisplayName("T14: principal.isCredentialsNonExpired() == false when entity.credentialsNonExpired == false")
        void t14_loadUserByUsername_mapsCredentialsNonExpiredFalseFromEntity() {
            User entity = entityWithDefaults("expiredcredsuser");
            entity.setCredentialsNonExpired(false);
            when(userRepository.findByUsername("expiredcredsuser")).thenReturn(Optional.of(entity));

            UserDetails principal = sut.loadUserByUsername("expiredcredsuser");

            // 7-arg constructor propagates entity flag to principal via delegate
            assertThat(principal.isCredentialsNonExpired())
                    .as("principal.isCredentialsNonExpired() must reflect entity.credentialsNonExpired == false; "
                            + "fails RED because 3-arg constructor defaults to true")
                    .isFalse();
        }
    }

    // =========================================================
    // T17 — loadUserByUsername must throw UsernameNotFoundException when user not
    // found
    // Expected: PASS
    // =========================================================

    @Nested
    @DisplayName("T17: loadUserByUsername throws UsernameNotFoundException for unknown user")
    class UserNotFoundPath {

        @Test
        @DisplayName("T17: throws UsernameNotFoundException with username in message when not found")
        void t17_loadUserByUsername_throwsUsernameNotFoundException_whenUserNotFound() {
            when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.loadUserByUsername("ghost"))
                    .isInstanceOf(UsernameNotFoundException.class)
                    .hasMessageContaining("ghost");
        }
    }

    // =========================================================
    // AUTH-005: SecurityUserPrincipal record accessors
    // Covers userId(), personId(), getUsername(), getPassword(), getAuthorities()
    // =========================================================

    @Nested
    @DisplayName("AUTH-005: SecurityUserPrincipal record accessor coverage")
    class SecurityUserPrincipalAccessors {

        @Test
        @DisplayName("T15: userId() returns the UUID from the User entity")
        void t15_userId_returnsEntityId() {
            UUID expectedId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
            User entity = entityWithDefaults("useridtest");
            entity.setId(expectedId);
            when(userRepository.findByUsername("useridtest")).thenReturn(Optional.of(entity));

            var principal = (CustomUserDetailsService.SecurityUserPrincipal) sut.loadUserByUsername("useridtest");

            assertThat(principal.userId()).isEqualTo(expectedId);
        }

        @Test
        @DisplayName("T16: personId() returns null when entity has no personId")
        void t16_personId_returnsNull_whenNotSet() {
            User entity = entityWithDefaults("nopersonid");
            when(userRepository.findByUsername("nopersonid")).thenReturn(Optional.of(entity));

            var principal = (CustomUserDetailsService.SecurityUserPrincipal) sut.loadUserByUsername("nopersonid");

            assertThat(principal.personId()).isNull();
        }

        @Test
        @DisplayName("T16b: personId() returns the UUID when entity has personId set")
        void t16b_personId_returnsUuid_whenSet() {
            UUID expectedPersonId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
            User entity = entityWithDefaults("withpersonid");
            entity.setPersonId(expectedPersonId);
            when(userRepository.findByUsername("withpersonid")).thenReturn(Optional.of(entity));

            var principal = (CustomUserDetailsService.SecurityUserPrincipal) sut.loadUserByUsername("withpersonid");

            assertThat(principal.personId()).isEqualTo(expectedPersonId);
        }

        @Test
        @DisplayName("T18: getUsername(), getPassword(), getAuthorities() delegate through the record")
        void t18_getUsername_getPassword_getAuthorities_delegateCorrectly() {
            User entity = entityWithDefaults("delegatetest");
            when(userRepository.findByUsername("delegatetest")).thenReturn(Optional.of(entity));

            UserDetails principal = sut.loadUserByUsername("delegatetest");

            assertThat(principal.getUsername()).isEqualTo("delegatetest");
            assertThat(principal.getPassword()).isEqualTo("{noop}password");
            assertThat(principal.getAuthorities()).isEmpty();
        }

        @Test
        @DisplayName("T19: effective role assignments contribute ROLE_* authorities")
        void t19_effectiveRoleAssignmentsContributeAuthorities() {
            User entity = entityWithDefaults("admin.alpha");
            Role role = new Role();
            role.setName("ADMIN");
            RoleAssignment assignment = new RoleAssignment();
            assignment.setUser(entity);
            assignment.setRole(role);
            assignment.setEffectiveStartDate(LocalDateTime.now());

            when(userRepository.findByUsername("admin.alpha")).thenReturn(Optional.of(entity));
            when(roleAssignmentRepository.findEffectiveAssignmentsByUser(entity)).thenReturn(List.of(assignment));

            UserDetails principal = sut.loadUserByUsername("admin.alpha");

            assertThat(principal.getAuthorities())
                    .extracting("authority")
                    .contains("ROLE_ADMIN");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Shared helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Creates a minimal non-persisted {@link User} with all account-state flags at
     * entity defaults, suitable for selective mutation before being returned by the
     * {@link UserRepository} mock.
     *
     * @param username the username to assign
     * @return a new, non-persisted {@link User} instance
     */
    private User entityWithDefaults(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("{noop}password");
        return user;
    }
}
