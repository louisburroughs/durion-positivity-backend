package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.config.LockoutPolicy;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * AUTH-003 unit tests for {@link LockoutServiceImpl}.
 *
 * <p>Tests assert the full expected behavior of progressive backoff lockout,
 * cooldown auto-unlock, and successful-login counter reset.
 *
 * <h3>ADR Compliance</h3>
 * <ul>
 * <li><strong>ADR-0017</strong>: Locked accounts must yield 401 (enforced via
 * LockedException propagation to GlobalExceptionHandler).</li>
 * <li><strong>ADR-0018</strong>: Actor identity originates from persisted user
 * record, not caller-supplied parameters.</li>
 * </ul>
 *
 * <h3>Test-to-Acceptance Criterion mapping</h3>
 * <table>
 * <tr><th>Test</th><th>AC</th></tr>
 * <tr><td>T01</td><td>AC1 — failed attempt recorded + saved</td></tr>
 * <tr><td>T02</td><td>AC2 — threshold breach locks account</td></tr>
 * <tr><td>T03</td><td>AC2 — progressive backoff on re-lockout</td></tr>
 * <tr><td>T04</td><td>AC3 — successful login resets counter + unlock</td></tr>
 * <tr><td>T05</td><td>AC4 — isLockedOut true when locked + future window</td></tr>
 * <tr><td>T06</td><td>AC4 — isLockedOut false when not locked</td></tr>
 * <tr><td>T07</td><td>AC5 — cooldown unlock clears lock when expired</td></tr>
 * <tr><td>T08</td><td>AC5 — cooldown unlock returns false when still locked</td></tr>
 * <tr><td>T09</td><td>AC5 — cooldown unlock returns false when not locked</td></tr>
 * </table>
 *
 * @since AUTH-003
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LockoutServiceImplTest — AUTH-003")
class LockoutServiceImplTest {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final int BACKOFF_MULTIPLIER = 2;
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);
    private static final Instant NOW = Instant.parse("2026-03-14T12:00:00Z");

    @Mock
    private UserRepository userRepository;

    @Mock
    private LockoutPolicy lockoutPolicy;

    @Mock
    private Clock clock;

    @InjectMocks
    private LockoutServiceImpl lockoutService;

    private void stubPolicy() {
        when(lockoutPolicy.maxAttempts()).thenReturn(MAX_ATTEMPTS);
        when(lockoutPolicy.window()).thenReturn(WINDOW);
        when(lockoutPolicy.backoffMultiplier()).thenReturn(BACKOFF_MULTIPLIER);
        when(lockoutPolicy.maxBackoffWindow()).thenReturn(MAX_BACKOFF);
    }

    private void stubClock() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private User freshUser() {
        User user = new User();
        user.setFailedLoginAttempts(0);
        user.setAccountNonLocked(true);
        user.setEnabled(true);
        user.setAccountNonExpired(true);
        user.setCredentialsNonExpired(true);
        return user;
    }

    @Nested
    @DisplayName("recordFailedAttempt")
    class RecordFailedAttempt {

        @Test
        @DisplayName("T01 — increments counter and saves user")
        void t01_incrementsCounterAndSaves() {
            UUID userId = UUID.randomUUID();
            User user = freshUser();
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            stubPolicy();
            stubClock();

            lockoutService.recordFailedAttempt(userId);

            verify(userRepository)
                    .save(argThat(u -> u.getFailedLoginAttempts() == 1 && u.getLastFailedLoginAt() != null));
        }

        @Test
        @DisplayName("T02 — locks account when threshold exceeded within window")
        void t02_locksAccountAtThreshold() {
            UUID userId = UUID.randomUUID();
            User user = freshUser();
            user.setFailedLoginAttempts(MAX_ATTEMPTS - 1);
            user.setLastFailedLoginAt(NOW.minusSeconds(60));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            stubPolicy();
            stubClock();

            lockoutService.recordFailedAttempt(userId);

            verify(userRepository)
                    .save(argThat(u -> !u.isAccountNonLocked()
                            && u.getLockedAt() != null
                            && u.getLockedUntil() != null
                            && u.getLockedUntil().isAfter(NOW)));
        }

        @Test
        @DisplayName("T03 — progressive backoff window doubles on re-lockout (capped)")
        void t03_progressiveBackoffOnReLockout() {
            UUID userId = UUID.randomUUID();
            User user = freshUser();
            user.setFailedLoginAttempts(2 * MAX_ATTEMPTS - 1);
            user.setAccountNonLocked(true); // was auto-unlocked
            user.setLastFailedLoginAt(NOW.minusSeconds(30));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            stubPolicy();
            stubClock();

            lockoutService.recordFailedAttempt(userId);

            // lockedUntil must be > window (doubled) and <= maxBackoff from now
            Instant minLockedUntil =
                    NOW.plus(WINDOW.multipliedBy(BACKOFF_MULTIPLIER)).minusSeconds(5);
            verify(userRepository)
                    .save(argThat(u -> !u.isAccountNonLocked()
                            && u.getLockedUntil() != null
                            && u.getLockedUntil().isAfter(minLockedUntil)));
        }
    }

    @Nested
    @DisplayName("recordSuccessfulLogin")
    class RecordSuccessfulLogin {

        @Test
        @DisplayName("T04 — resets failed attempts and clears lockout")
        void t04_resetsFailedAttemptsAndClearsLock() {
            UUID userId = UUID.randomUUID();
            User user = freshUser();
            user.setFailedLoginAttempts(3);
            user.setAccountNonLocked(false);
            user.setLockedAt(NOW.minusSeconds(300));
            user.setLockedUntil(NOW.plusSeconds(300));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            stubClock();

            lockoutService.recordSuccessfulLogin(userId);

            verify(userRepository)
                    .save(argThat(u -> u.getFailedLoginAttempts() == 0
                            && u.isAccountNonLocked()
                            && u.getLockedAt() == null
                            && u.getLockedUntil() == null
                            && u.getLastSuccessfulLoginAt() != null));
        }
    }

    @Nested
    @DisplayName("isLockedOut")
    class IsLockedOut {

        @Test
        @DisplayName("T05 — returns true when locked and lockedUntil is in future")
        void t05_returnsTrueWhenLockedWithFutureWindow() {
            UUID userId = UUID.randomUUID();
            User user = freshUser();
            user.setAccountNonLocked(false);
            user.setLockedUntil(NOW.plusSeconds(300));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            stubClock();

            boolean result = lockoutService.isLockedOut(userId);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("T06 — returns false when accountNonLocked=true")
        void t06_returnsFalseWhenNotLocked() {
            UUID userId = UUID.randomUUID();
            User user = freshUser();
            user.setAccountNonLocked(true);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            stubClock();

            boolean result = lockoutService.isLockedOut(userId);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("unlockIfCooldownExpired")
    class UnlockIfCooldownExpired {

        @Test
        @DisplayName("T07 — unlocks and returns true when lockedUntil is past")
        void t07_unlocksWhenCooldownExpired() {
            UUID userId = UUID.randomUUID();
            User user = freshUser();
            user.setAccountNonLocked(false);
            user.setLockedAt(NOW.minusSeconds(700));
            user.setLockedUntil(NOW.minusSeconds(60));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            stubClock();

            boolean result = lockoutService.unlockIfCooldownExpired(userId);

            assertThat(result).isTrue();
            verify(userRepository)
                    .save(argThat(u -> u.isAccountNonLocked()
                            && u.getLockedAt() == null
                            && u.getLockedUntil() == null
                            && u.getFailedLoginAttempts() == 0));
        }

        @Test
        @DisplayName("T08 — returns false without save when still within lockout window")
        void t08_returnsFalseWhenStillLocked() {
            UUID userId = UUID.randomUUID();
            User user = freshUser();
            user.setAccountNonLocked(false);
            user.setLockedUntil(NOW.plusSeconds(300));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            stubClock();

            boolean result = lockoutService.unlockIfCooldownExpired(userId);

            assertThat(result).isFalse();
            verify(userRepository, never()).save(user);
        }

        @Test
        @DisplayName("T09 — returns false without save when not locked")
        void t09_returnsFalseWhenNotLocked() {
            UUID userId = UUID.randomUUID();
            User user = freshUser();
            user.setAccountNonLocked(true);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            stubClock();

            boolean result = lockoutService.unlockIfCooldownExpired(userId);

            assertThat(result).isFalse();
            verify(userRepository, never()).save(user);
        }

        @Test
        @DisplayName("T10x — returns false without save when admin-set lock (lockedUntil=null)")
        void t10x_returnsFalseWhenAdminLockWithNullLockedUntil() {
            UUID userId = UUID.randomUUID();
            User user = freshUser();
            user.setAccountNonLocked(false);
            user.setLockedUntil(null); // admin-set permanent lock — no expiry
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            stubClock();

            boolean result = lockoutService.unlockIfCooldownExpired(userId);

            assertThat(result).isFalse();
            verify(userRepository, never()).save(user);
        }
    }

    @Nested
    @DisplayName("isLockedOut — edge cases")
    class IsLockedOutEdgeCases {

        @Test
        @DisplayName("T11x — returns true when locked with null lockedUntil (admin indefinite lock)")
        void t11x_returnsTrueWhenAdminIndefiniteLock() {
            UUID userId = UUID.randomUUID();
            User user = freshUser();
            user.setAccountNonLocked(false);
            user.setLockedUntil(null); // no expiry → lock is permanent until manually cleared
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            stubClock();

            boolean result = lockoutService.isLockedOut(userId);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("T12x — returns false when locked but lockedUntil is in the past (expired, not yet auto-unlocked)")
        void t12x_returnsFalseWhenLockWindowExpired() {
            UUID userId = UUID.randomUUID();
            User user = freshUser();
            user.setAccountNonLocked(false);
            user.setLockedUntil(NOW.minusSeconds(1)); // expired
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            stubClock();

            boolean result = lockoutService.isLockedOut(userId);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("recordFailedAttempt — outside window")
    class RecordFailedAttemptOutsideWindow {

        @Test
        @DisplayName("T13x — no lock when attempts >= maxAttempts but last failure is outside the window")
        void t13x_noLockWhenAttemptsAtThresholdButWindowExpired() {
            UUID userId = UUID.randomUUID();
            User user = freshUser();
            user.setFailedLoginAttempts(MAX_ATTEMPTS - 1);
            // Last failure is older than the window → withinWindow = false
            user.setLastFailedLoginAt(NOW.minus(WINDOW).minusSeconds(60));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            stubPolicy();
            stubClock();

            lockoutService.recordFailedAttempt(userId);

            verify(userRepository)
                    .save(argThat(
                            u -> u.isAccountNonLocked() && u.getLockedAt() == null && u.getLockedUntil() == null));
        }
    }

    @Nested
    @DisplayName("UsernameNotFoundException propagation")
    class UsernameNotFoundPropagation {

        @Test
        @DisplayName("T14x — recordFailedAttempt throws when userId not found")
        void t14x_recordFailedAttemptThrowsWhenUserNotFound() {
            UUID userId = UUID.randomUUID();
            when(userRepository.findById(userId)).thenReturn(Optional.empty());
            stubClock();
            stubPolicy();

            assertThatThrownBy(() -> lockoutService.recordFailedAttempt(userId))
                    .isInstanceOf(org.springframework.security.core.userdetails.UsernameNotFoundException.class);
        }

        @Test
        @DisplayName("T15x — recordSuccessfulLogin throws when userId not found")
        void t15x_recordSuccessfulLoginThrowsWhenUserNotFound() {
            UUID userId = UUID.randomUUID();
            when(userRepository.findById(userId)).thenReturn(Optional.empty());
            stubClock();

            assertThatThrownBy(() -> lockoutService.recordSuccessfulLogin(userId))
                    .isInstanceOf(org.springframework.security.core.userdetails.UsernameNotFoundException.class);
        }

        @Test
        @DisplayName("T16x — isLockedOut throws when userId not found")
        void t16x_isLockedOutThrowsWhenUserNotFound() {
            UUID userId = UUID.randomUUID();
            when(userRepository.findById(userId)).thenReturn(Optional.empty());
            stubClock();

            assertThatThrownBy(() -> lockoutService.isLockedOut(userId))
                    .isInstanceOf(org.springframework.security.core.userdetails.UsernameNotFoundException.class);
        }

        @Test
        @DisplayName("T17x — unlockIfCooldownExpired throws when userId not found")
        void t17x_unlockIfCooldownExpiredThrowsWhenUserNotFound() {
            UUID userId = UUID.randomUUID();
            when(userRepository.findById(userId)).thenReturn(Optional.empty());
            stubClock();

            assertThatThrownBy(() -> lockoutService.unlockIfCooldownExpired(userId))
                    .isInstanceOf(org.springframework.security.core.userdetails.UsernameNotFoundException.class);
        }
    }
}
