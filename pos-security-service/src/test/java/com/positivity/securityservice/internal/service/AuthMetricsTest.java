package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.dto.LoginRequest;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.service.JwtService;
import com.positivity.securityservice.service.JwtService.TokenPair;
import com.positivity.securityservice.service.LockoutService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;

/**
 * Unit tests asserting that {@link AuthenticationServiceImpl} emits Micrometer
 * counters for authentication outcomes.
 *
 * <h3>RED phase</h3>
 * These tests FAIL in RED because {@code AuthenticationServiceImpl} does not
 * yet accept or use a {@link io.micrometer.core.instrument.MeterRegistry}.
 * Mockito's {@code @InjectMocks} constructs the service with its current
 * 4-parameter constructor, leaving the {@code @Spy SimpleMeterRegistry} uninjected.
 * All counter-count assertions fail (0.0 != 1.0).
 *
 * <h3>GREEN phase</h3>
 * {@code AuthenticationServiceImpl} gains a {@code MeterRegistry} constructor
 * parameter. {@code @InjectMocks} then injects the {@code @Spy SimpleMeterRegistry},
 * the service increments the expected counters, and the assertions pass.
 *
 * <h3>Counter contract</h3>
 * <ul>
 *   <li>{@code auth.login.success} — incremented once on successful login</li>
 *   <li>{@code auth.login.failure[reason=bad_credentials]} — incremented on
 *       {@link BadCredentialsException}</li>
 *   <li>{@code auth.login.failure[reason=account_locked]} — incremented when
 *       the lockout service denies access before {@code authenticate()} is called</li>
 *   <li>{@code auth.login.failure[reason=account_disabled]} — incremented on
 *       {@link DisabledException}</li>
 * </ul>
 *
 * Issue: AUTH-008
 *
 * @since AUTH-008
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthMetricsTest — AUTH-008 Micrometer counter emission")
class AuthMetricsTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtService jwtService;

    @Mock
    private LockoutService lockoutService;

    @Mock
    private UserRepository userRepository;

    /**
     * Real {@code SimpleMeterRegistry} so counter increments are directly observable.
     * Declared as {@code @Spy} so Mockito's {@code @InjectMocks} machinery will
     * inject it into {@code AuthenticationServiceImpl} once GREEN adds the
     * {@code MeterRegistry} constructor parameter.
     */
    @Spy
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @InjectMocks
    private AuthenticationServiceImpl sut;

    // ---------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------

    private com.positivity.securityservice.internal.entity.User userEntity(UUID userId) {
        com.positivity.securityservice.internal.entity.User u =
                new com.positivity.securityservice.internal.entity.User();
        u.setId(userId);
        u.setUsername("alice");
        u.setAccountNonLocked(true);
        u.setEnabled(true);
        u.setAccountNonExpired(true);
        u.setCredentialsNonExpired(true);
        u.setFailedLoginAttempts(0);
        return u;
    }

    private Authentication successAuth(UUID userId) {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("alice");
        when(auth.getPrincipal())
                .thenReturn(new CustomUserDetailsService.SecurityUserPrincipal(
                        userId, null, new User("alice", "pass", List.of())));
        return auth;
    }

    // =========================================================
    // T_AM1 — auth.login.success counter incremented on successful login
    // =========================================================

    @Nested
    @DisplayName("T_AM1: auth.login.success counter incremented on successful login")
    class SuccessCounter {

        @Test
        @DisplayName("T_AM1 — login() increments auth.login.success by 1 on success (RED: 0.0 != 1.0)")
        void authenticate_success_incrementsAuthSuccessCounter() {
            UUID userId = UUID.randomUUID();
            Authentication auth = successAuth(userId);
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(userEntity(userId)));
            when(lockoutService.isLockedOut(userId)).thenReturn(false);
            when(authenticationManager.authenticate(any())).thenReturn(auth);
            when(jwtService.generateTokenPair(any(), any(), any(), any()))
                    .thenReturn(new TokenPair("access.stub", "refresh.stub"));

            sut.login(new LoginRequest("alice", "pass"));

            // Issue AUTH-008: RED — auth.login.success counter is 0 until instrumentation is added
            double count = registry.counter("auth.login.success").count();
            assertThat(count)
                    .as("auth.login.success counter must be incremented to 1 after a successful login")
                    .isEqualTo(1.0);
        }
    }

    // =========================================================
    // T_AM2 — auth.login.failure[reason=bad_credentials] incremented on BadCredentialsException
    // =========================================================

    @Nested
    @DisplayName("T_AM2: auth.login.failure[reason=bad_credentials] incremented on BadCredentialsException")
    class BadCredentialsCounter {

        @Test
        @DisplayName("T_AM2 — login() increments auth.login.failure[reason=bad_credentials] by 1 (RED: 0.0 != 1.0)")
        void authenticate_invalidCredentials_incrementsAuthFailureCounter() {
            UUID userId = UUID.randomUUID();
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(userEntity(userId)));
            when(lockoutService.isLockedOut(userId)).thenReturn(false);
            when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

            assertThatThrownBy(() -> sut.login(new LoginRequest("alice", "wrong")))
                    .isInstanceOf(BadCredentialsException.class);

            // Issue AUTH-008: RED — counter stays at 0 until auth failure instrumentation is added
            double count = registry.counter("auth.login.failure", "reason", "bad_credentials")
                    .count();
            assertThat(count)
                    .as("auth.login.failure[reason=bad_credentials] must be incremented to 1 after bad credentials")
                    .isEqualTo(1.0);
        }
    }

    // =========================================================
    // T_AM3 — auth.login.failure[reason=account_locked] incremented when lockout service denies
    // =========================================================

    @Nested
    @DisplayName("T_AM3: auth.login.failure[reason=account_locked] incremented when lockout pre-check denies")
    class LockoutDenialCounter {

        @Test
        @DisplayName("T_AM3 — login() increments auth.login.failure[reason=account_locked] by 1 (RED: 0.0 != 1.0)")
        void authenticate_accountLocked_incrementsLockoutDenialCounter() {
            UUID userId = UUID.randomUUID();
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(userEntity(userId)));
            when(lockoutService.unlockIfCooldownExpired(userId)).thenReturn(false);
            when(lockoutService.isLockedOut(userId)).thenReturn(true);

            assertThatThrownBy(() -> sut.login(new LoginRequest("alice", "pass")))
                    .isInstanceOf(LockedException.class);

            // Issue AUTH-008: RED — counter stays at 0 until lockout denial instrumentation is added
            double count = registry.counter("auth.login.failure", "reason", "account_locked")
                    .count();
            assertThat(count)
                    .as(
                            "auth.login.failure[reason=account_locked] must be incremented to 1 when lockout pre-check fires")
                    .isEqualTo(1.0);
        }
    }

    // =========================================================
    // T_AM4 — auth.login.failure[reason=account_disabled] incremented on DisabledException
    // =========================================================

    @Nested
    @DisplayName("T_AM4: auth.login.failure[reason=account_disabled] incremented on DisabledException")
    class DisabledAccountCounter {

        @Test
        @DisplayName("T_AM4 — login() increments auth.login.failure[reason=account_disabled] by 1 (RED: 0.0 != 1.0)")
        void authenticate_accountDisabled_incrementsAuthFailureCounter() {
            UUID userId = UUID.randomUUID();
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(userEntity(userId)));
            when(lockoutService.isLockedOut(userId)).thenReturn(false);
            when(authenticationManager.authenticate(any())).thenThrow(new DisabledException("Account is disabled"));

            assertThatThrownBy(() -> sut.login(new LoginRequest("alice", "pass")))
                    .isInstanceOf(DisabledException.class);

            // Issue AUTH-008: RED — counter stays at 0 until disabled-account instrumentation is added
            double count = registry.counter("auth.login.failure", "reason", "account_disabled")
                    .count();
            assertThat(count)
                    .as("auth.login.failure[reason=account_disabled] must be incremented to 1 when account is disabled")
                    .isEqualTo(1.0);
        }
    }
}
