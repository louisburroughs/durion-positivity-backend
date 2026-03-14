package com.positivity.securityservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.positivity.securityservice.internal.dto.AccountStateResponse;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.service.AdminAccountStateServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link AdminAccountStateServiceImpl} — AUTH-006.
 *
 * <p>Verifies unlock, enable, disable, expire-account, expire-credentials, and
 * account-state-read operations: audit actor resolution from security context
 * (ADR-0018), field mutations, and UserNotFoundException propagation.
 *
 * <p>All tests use a fixed {@link Clock} for deterministic time assertions.
 *
 * Issue: AUTH-006
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAccountStateServiceTest — AUTH-006")
class AdminAccountStateServiceTest {

    private static final Clock TEST_CLOCK =
            Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    UserRepository userRepository;

    @InjectMocks
    AdminAccountStateServiceImpl service;

    @AfterEach
    void cleanupSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── Unlock ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Unlock")
    class Unlock {

        /**
         * Verifies that unlocking a locked user clears all lockout fields.
         */
        @Test
        @DisplayName("unlock() clears all lock fields")
        void unlock_clearsLockFields() {
            User locked = new User();
            locked.setId(USER_ID);
            locked.setAccountNonLocked(false);
            locked.setFailedLoginAttempts(5);
            locked.setLockedAt(TEST_CLOCK.instant());
            locked.setLockedUntil(TEST_CLOCK.instant().plusSeconds(600));

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(locked));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            service.unlock(USER_ID);

            verify(userRepository).save(any(User.class));
            assertThat(locked.isAccountNonLocked()).isTrue();
            assertThat(locked.getFailedLoginAttempts()).isZero();
            assertThat(locked.getLockedAt()).isNull();
            assertThat(locked.getLockedUntil()).isNull();
        }

        @Test
        @DisplayName("unlock() throws UserNotFoundException when user is missing")
        void unlock_throwsUserNotFoundWhenMissing() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.unlock(USER_ID))
                    .isInstanceOf(UserNotFoundException.class);
        }
    }

    // ── Enable ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Enable")
    class Enable {

        /**
         * Verifies that enabling a disabled user sets {@code enabled=true} and
         * persists the change.
         */
        @Test
        @DisplayName("enable() sets enabled=true")
        void enable_setsEnabledTrue() {
            User disabled = new User();
            disabled.setId(USER_ID);
            disabled.setEnabled(false);
            disabled.setDisabledAt(TEST_CLOCK.instant());
            disabled.setDisabledBy("previous-admin");

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(disabled));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            service.enable(USER_ID);

            verify(userRepository).save(any(User.class));
            assertThat(disabled.isEnabled()).isTrue();
            // disabledAt and disabledBy are retained as audit history after re-enable
            assertThat(disabled.getDisabledAt()).isEqualTo(TEST_CLOCK.instant());
            assertThat(disabled.getDisabledBy()).isEqualTo("previous-admin");
        }

        @Test
        @DisplayName("enable() throws UserNotFoundException when user is missing")
        void enable_throwsUserNotFoundWhenMissing() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.enable(USER_ID))
                    .isInstanceOf(UserNotFoundException.class);
        }
    }

    // ── Disable ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Disable")
    class Disable {

        /**
         * Verifies that disabling a user sets {@code enabled=false}, records the actor
         * from the security context (ADR-0018), and persists {@code disabledAt} from
         * the injected clock.
         */
        @Test
        @DisplayName("disable() sets enabled=false and records audit fields from security context")
        void disable_setsEnabledFalseAndRecordsAudit() {
            User active = new User();
            active.setId(USER_ID);
            active.setEnabled(true);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(active));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            // Issue AUTH-006: actor resolved server-side from security context (ADR-0018)
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            "admin-user", "n/a",
                            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

            service.disable(USER_ID);

            verify(userRepository).save(any(User.class));
            assertThat(active.isEnabled()).isFalse();
            assertThat(active.getDisabledBy()).isEqualTo("admin-user");
            assertThat(active.getDisabledAt()).isEqualTo(TEST_CLOCK.instant());
        }

        @Test
        @DisplayName("disable() throws UserNotFoundException when user is missing")
        void disable_throwsUserNotFoundWhenMissing() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.disable(USER_ID))
                    .isInstanceOf(UserNotFoundException.class);
        }
    }

    // ── ExpireAccount ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ExpireAccount")
    class ExpireAccount {

        /**
         * Verifies that expiring an account sets {@code accountNonExpired=false} and
         * records a non-null {@code accountExpiresAt} timestamp.
         */
        @Test
        @DisplayName("expireAccount() sets accountNonExpired=false and records accountExpiresAt")
        void expireAccount_setsAccountNonExpiredFalse() {
            User user = new User();
            user.setId(USER_ID);
            user.setAccountNonExpired(true);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            service.expireAccount(USER_ID);

            verify(userRepository).save(any(User.class));
            assertThat(user.isAccountNonExpired()).isFalse();
            assertThat(user.getAccountExpiresAt()).isEqualTo(TEST_CLOCK.instant());
        }

        @Test
        @DisplayName("expireAccount() throws UserNotFoundException when user is missing")
        void expireAccount_throwsUserNotFoundWhenMissing() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.expireAccount(USER_ID))
                    .isInstanceOf(UserNotFoundException.class);
        }
    }

    // ── ExpireCredentials ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("ExpireCredentials")
    class ExpireCredentials {

        /**
         * Verifies that expiring credentials sets {@code credentialsNonExpired=false}
         * and records a non-null {@code credentialsExpireAt} timestamp.
         */
        @Test
        @DisplayName("expireCredentials() sets credentialsNonExpired=false and records credentialsExpireAt")
        void expireCredentials_setsCredentialsNonExpiredFalse() {
            User user = new User();
            user.setId(USER_ID);
            user.setCredentialsNonExpired(true);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            service.expireCredentials(USER_ID);

            verify(userRepository).save(any(User.class));
            assertThat(user.isCredentialsNonExpired()).isFalse();
            assertThat(user.getCredentialsExpireAt()).isEqualTo(TEST_CLOCK.instant());
        }

        @Test
        @DisplayName("expireCredentials() throws UserNotFoundException when user is missing")
        void expireCredentials_throwsUserNotFoundWhenMissing() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.expireCredentials(USER_ID))
                    .isInstanceOf(UserNotFoundException.class);
        }
    }

    // ── GetAccountState ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("GetAccountState")
    class GetAccountState {

        /**
         * Verifies that {@code getAccountState()} maps the user's state fields to an
         * {@link AccountStateResponse} with correct values.
         */
        @Test
        @DisplayName("getAccountState() maps user fields to AccountStateResponse correctly")
        void getAccountState_returnsCorrectState() {
            User locked = new User();
            locked.setId(USER_ID);
            locked.setEnabled(true);
            locked.setAccountNonLocked(false);
            locked.setFailedLoginAttempts(5);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(locked));

            AccountStateResponse response = service.getAccountState(USER_ID);

            assertThat(response.getUserId()).isEqualTo(USER_ID);
            assertThat(response.isEnabled()).isTrue();
            assertThat(response.isAccountNonLocked()).isFalse();
            assertThat(response.getFailedLoginAttempts()).isEqualTo(5);
        }

        @Test
        @DisplayName("getAccountState() throws UserNotFoundException when user is missing")
        void getAccountState_throwsUserNotFoundWhenMissing() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAccountState(USER_ID))
                    .isInstanceOf(UserNotFoundException.class);
        }
    }
}
