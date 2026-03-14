package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.positivity.securityservice.internal.dto.LoginRequest;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.service.JwtService;
import com.positivity.securityservice.service.JwtService.TokenPair;
import com.positivity.securityservice.service.LockoutService;

import org.springframework.security.authentication.LockedException;

/**
 * Unit tests for {@link AuthenticationServiceImpl} covering AUTH-001 and AUTH-003.
 *
 * <h3>ADR Compliance</h3>
 * <ul>
 * <li><strong>ADR-0017</strong>: 401 for invalid credentials and locked accounts, 200 on
 * success.</li>
 * <li><strong>ADR-0018</strong>: Actor identity comes from the security context
 * (populated by AuthenticationManager), not raw request parameters.</li>
 * </ul>
 *
 * <h3>Test-to-Acceptance Criterion mapping</h3>
 * <table>
 * <tr>
 * <th>Test</th>
 * <th>AC</th>
 * </tr>
 * <tr>
 * <td>T7</td>
 * <td>AUTH-001 AC2: no direct PasswordEncoder usage</td>
 * </tr>
 * <tr>
 * <td>T8</td>
 * <td>AUTH-001 AC2, AC6: delegates to AuthenticationManager</td>
 * </tr>
 * <tr>
 * <td>T9</td>
 * <td>AUTH-001 AC3, AC6: calls jwtService.generateTokenPair on success</td>
 * </tr>
 * <tr>
 * <td>T10-T12</td>
 * <td>AUTH-003 AC3, AC4: lockout integration (pre-check, success bookkeeping, failure bookkeeping)</td>
 * </tr>
 * </table>
 *
 * @since AUTH-001
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthenticationServiceImplTest — AUTH-001 / AUTH-003")
class AuthenticationServiceImplTest {

    @Mock
    private AuthenticationManager authenticationManager;

    /**
     * LockoutService mock — wired into sut via @InjectMocks once GREEN adds the
     * field. T10-T12 verify lockout pre-check and bookkeeping behavior.
     */
    @Mock
    private LockoutService lockoutService;

    /**
     * UserRepository mock — required by T12 so userId can be resolved before
     * authenticate() is called.
     */
    @Mock
    private UserRepository userRepository;

    /**
     * JwtService mock — wired via constructor injection into AuthenticationServiceImpl.
     */
    @Mock
    private JwtService jwtService;

    /**
     * PasswordEncoder mock — intentionally declared to support T7's
     * "never called" verification. It is NOT expected to be injected into
     * {@code AuthenticationServiceImpl}; the impl must NOT depend on it.
     */
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthenticationServiceImpl sut;

    // =========================================================
    // T7 — login() must NOT use PasswordEncoder directly (AC2)
    // =========================================================

    @Nested
    @DisplayName("T7: login() does not use PasswordEncoder directly (AC2)")
    class NoBcryptDirectUse {

        @Test
        @DisplayName("T7a [architectural]: AuthenticationServiceImpl has no PasswordEncoder field")
        void impl_doesNotDeclarePasswordEncoderField() {
            // This architectural assertion holds in BOTH RED and GREEN phases:
            // the impl must never own a PasswordEncoder dependency.
            boolean hasPasswordEncoderField = java.util.Arrays.stream(
                    AuthenticationServiceImpl.class.getDeclaredFields())
                    .anyMatch(f -> f.getType().equals(PasswordEncoder.class));

            assertThat(hasPasswordEncoderField)
                    .as("AuthenticationServiceImpl must not declare a PasswordEncoder field; "
                            + "credential verification is delegated to AuthenticationManager")
                    .isFalse();
        }

        @Test
        @DisplayName("T7b: login() completes without calling PasswordEncoder.matches()")
        void login_neverCallsPasswordEncoderMatches() {
            Authentication successAuth = mock(Authentication.class);
            when(successAuth.getName()).thenReturn("testuser");
            when(successAuth.getPrincipal()).thenReturn(
                    new CustomUserDetailsService.SecurityUserPrincipal(UUID.randomUUID(),
                            new User("testuser", "password", List.of())));
            when(authenticationManager.authenticate(any())).thenReturn(successAuth);
            when(jwtService.generateTokenPair(any(), any(), any()))
                    .thenReturn(new TokenPair("access.stub", "refresh.stub"));

            // GREEN: completes; verify PasswordEncoder.matches() was never called.
            sut.login(new LoginRequest("testuser", "password"));

            // PasswordEncoder.matches() must never be called directly in the impl.
            verify(passwordEncoder, never()).matches(any(), any());
        }
    }

    // =========================================================
    // T8 — login() delegates to AuthenticationManager (AC2)
    // =========================================================

    @Nested
    @DisplayName("T8: login() delegates to AuthenticationManager with UPAT (AC2)")
    class DelegatesToAuthManager {

        @Test
        @DisplayName("T8: login() calls authenticationManager.authenticate() with UsernamePasswordAuthenticationToken")
        void login_delegatesToAuthenticationManager() {
            Authentication successAuth = mock(Authentication.class);
            when(successAuth.getName()).thenReturn("alice");
            when(successAuth.getPrincipal()).thenReturn(
                    new CustomUserDetailsService.SecurityUserPrincipal(UUID.randomUUID(),
                            new User("alice", "secret", List.of())));
            when(authenticationManager.authenticate(any())).thenReturn(successAuth);
            when(jwtService.generateTokenPair(any(), any(), any()))
                    .thenReturn(new TokenPair("access.stub", "refresh.stub"));

            // GREEN: delegates to authManager, completes normally.
            sut.login(new LoginRequest("alice", "secret"));

            // Verify the correct token type was used.
            verify(authenticationManager)
                    .authenticate(argThat(token -> token instanceof UsernamePasswordAuthenticationToken upat
                            && "alice".equals(upat.getName())
                            && "secret".equals(upat.getCredentials())));
        }

        @Test
        @DisplayName("T8b : login() propagates BadCredentialsException from AuthenticationManager")
        void login_propagatesBadCredentialsException() {
            when(authenticationManager.authenticate(any()))
                    .thenThrow(new BadCredentialsException("Invalid credentials"));

            // isInstanceOf(AuthenticationException) fails.
            // GREEN: impl delegates to authManager → BadCredentialsException propagates →
            // passes.
            assertThatThrownBy(() -> sut.login(new LoginRequest("alice", "badpassword")))
                    .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        }
    }

    // =========================================================
    // T8c — login() throws IllegalStateException for unexpected principal type
    // =========================================================

    @Nested
    @DisplayName("T8c: login() throws IllegalStateException when principal is not SecurityUserPrincipal")
    class UnexpectedPrincipalType {

        @Test
        @DisplayName("T8c: login() throws IllegalStateException when AuthenticationManager returns non-SecurityUserPrincipal")
        void login_throwsIllegalStateException_whenPrincipalIsNotSecurityUserPrincipal() {
            Authentication successAuth = mock(Authentication.class);
            when(successAuth.getName()).thenReturn("testuser");
            // Return a plain String as principal — not a SecurityUserPrincipal
            when(successAuth.getPrincipal()).thenReturn("unexpected-string-principal");
            when(authenticationManager.authenticate(any())).thenReturn(successAuth);

            assertThatThrownBy(() -> sut.login(new LoginRequest("testuser", "password")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unexpected principal type")
                    .hasMessageContaining("java.lang.String");
        }
    }

    // =========================================================
    // T9 — login() calls JwtService.generateTokenPair() on success (AC3, AC6)
    // =========================================================

    @Nested
    @DisplayName("T9: login() calls JwtService.generateTokenPair() on success (AC3, AC6)")
    class DelegatesToJwtService {

        @Test
        @DisplayName("T9 : login() calls jwtService.generateTokenPair() and returns TokenPairResponse")
        void login_callsJwtServiceGenerateTokenPairOnSuccess() {
            Authentication successAuth = mock(Authentication.class);
            when(successAuth.getName()).thenReturn("bob");
            when(successAuth.getPrincipal()).thenReturn(
                    new CustomUserDetailsService.SecurityUserPrincipal(UUID.randomUUID(),
                            new User("bob", "pass", List.of())));
            when(authenticationManager.authenticate(any())).thenReturn(successAuth);

            String fakeAccess = "access.token.stub";
            String fakeRefresh = "refresh.token.stub";
            when(jwtService.generateTokenPair(any(), any(), any()))
                    .thenReturn(new TokenPair(fakeAccess, fakeRefresh));

            // reached.
            var response = sut.login(new LoginRequest("bob", "pass"));

            assertThat(response.accessToken())
                    .as("accessToken must equal value returned by JwtService")
                    .isEqualTo(fakeAccess);
            assertThat(response.refreshToken())
                    .as("refreshToken must equal value returned by JwtService")
                    .isEqualTo(fakeRefresh);

            verify(jwtService).generateTokenPair(
                    argThat("bob"::equals),
                    any(UUID.class),
                    any());
        }
    }

    // =========================================================
    // T10-T12 — Lockout integration in AuthenticationServiceImpl (AUTH-003)
    // fail with NullPointerException or because lockout pre-check is absent.
    // =========================================================

    @Nested
    @DisplayName("T10-T12: lockout integration (AUTH-003 AC3, AC4)")
    class LockoutIntegration {

        private final UUID userId = UUID.randomUUID();

        private com.positivity.securityservice.internal.entity.User userEntity() {
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

        @Test
        @DisplayName("T10 — throws LockedException before authenticate() when isLockedOut=true")
        void t10_throwsLockedExceptionWhenIsLockedOut() {
            when(userRepository.findByUsername("alice")).thenReturn(
                    java.util.Optional.of(userEntity()));
            when(lockoutService.unlockIfCooldownExpired(userId)).thenReturn(false);
            when(lockoutService.isLockedOut(userId)).thenReturn(true);

            assertThatThrownBy(() -> sut.login(new LoginRequest("alice", "pass")))
                    .isInstanceOf(LockedException.class);

            verify(authenticationManager, never()).authenticate(any());
        }

        @Test
        @DisplayName("T11 — calls recordSuccessfulLogin after successful authenticate")
        void t11_callsRecordSuccessfulLoginOnSuccess() {
            when(userRepository.findByUsername("alice")).thenReturn(
                    java.util.Optional.of(userEntity()));
            when(lockoutService.unlockIfCooldownExpired(userId)).thenReturn(false);
            when(lockoutService.isLockedOut(userId)).thenReturn(false);

            Authentication successAuth = mock(Authentication.class);
            when(successAuth.getName()).thenReturn("alice");
            when(successAuth.getPrincipal()).thenReturn(
                    new CustomUserDetailsService.SecurityUserPrincipal(
                            userId,
                            new org.springframework.security.core.userdetails.User(
                                    "alice", "pass", List.of())));
            when(authenticationManager.authenticate(any())).thenReturn(successAuth);
            when(jwtService.generateTokenPair(any(), any(), any()))
                    .thenReturn(new TokenPair("a", "r"));

            sut.login(new LoginRequest("alice", "pass"));

            verify(lockoutService).recordSuccessfulLogin(userId);
        }

        @Test
        @DisplayName("T12 — calls recordFailedAttempt and rethrows BadCredentials")
        void t12_callsRecordFailedAttemptOnBadCredentials() {
            when(userRepository.findByUsername("alice")).thenReturn(
                    java.util.Optional.of(userEntity()));
            when(lockoutService.unlockIfCooldownExpired(userId)).thenReturn(false);
            when(lockoutService.isLockedOut(userId)).thenReturn(false);
            when(authenticationManager.authenticate(any()))
                    .thenThrow(new BadCredentialsException("bad"));

            assertThatThrownBy(() -> sut.login(new LoginRequest("alice", "pass")))
                    .isInstanceOf(BadCredentialsException.class);

            verify(lockoutService).recordFailedAttempt(userId);
        }
    }
}
