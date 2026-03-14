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
import com.positivity.securityservice.service.JwtService;
import com.positivity.securityservice.service.JwtService.TokenPair;

/**
 * AUTH-001 RED unit tests for {@link AuthenticationServiceImpl}.
 *
 * <h3>ADR Compliance</h3>
 * <ul>
 *   <li><strong>ADR-0017</strong>: 401 for invalid credentials, 200 on success.</li>
 *   <li><strong>ADR-0018</strong>: Actor identity comes from the security context
 *       (populated by AuthenticationManager), not raw request parameters.</li>
 * </ul>
 *
 * <h3>Test-to-Acceptance Criterion mapping</h3>
 * <table>
 *   <tr><th>Test</th><th>AC</th><th>RED?</th><th>Failure reason in RED phase</th></tr>
 *   <tr><td>T7</td><td>AC2</td><td>YES</td>
 *       <td>Stub throws UnsupportedOperationException; PasswordEncoder field absence
 *           assertion is architectural (passes) but the behavioural assertion on the
 *           returned value fails.</td></tr>
 *   <tr><td>T8</td><td>AC2, AC6</td><td>YES</td>
 *       <td>Stub throws UnsupportedOperationException; verify() on authenticationManager
 *           is never reached.</td></tr>
 *   <tr><td>T9</td><td>AC3, AC6</td><td>YES</td>
 *       <td>Stub throws UnsupportedOperationException; verify() on jwtService is
 *           never reached.</td></tr>
 * </table>
 *
 * @since AUTH-001
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthenticationServiceImplTest — AUTH-001")
class AuthenticationServiceImplTest {

    @Mock
    private AuthenticationManager authenticationManager;

    /**
     * JwtService mock.
     *
     * <p>In RED phase, {@code AuthenticationServiceImpl} does not yet declare
     * a {@code JwtService} field, so {@code @InjectMocks} will not wire this
     * mock into the stub.  Once GREEN implementation adds the field, Mockito's
     * constructor injection will wire it automatically.
     */
    @Mock
    private JwtService jwtService;

    /**
     * PasswordEncoder mock — intentionally declared to support T7's
     * "never called" verification.  It is NOT expected to be injected into
     * {@code AuthenticationServiceImpl}; the impl must NOT depend on it.
     */
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthenticationServiceImpl sut;

    // =========================================================
    // T7 — login() must NOT use PasswordEncoder directly (AC2)
    // RED: stub throws UnsupportedOperationException
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
    // RED: stub throws UnsupportedOperationException
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
            verify(authenticationManager).authenticate(argThat(token ->
                    token instanceof UsernamePasswordAuthenticationToken upat
                            && "alice".equals(upat.getName())
                            && "secret".equals(upat.getCredentials())));
        }

        @Test
        @DisplayName("T8b [RED]: login() propagates BadCredentialsException from AuthenticationManager")
        void login_propagatesBadCredentialsException() {
            when(authenticationManager.authenticate(any()))
                    .thenThrow(new BadCredentialsException("Invalid credentials"));

            // RED: stub throws UnsupportedOperationException → isInstanceOf(AuthenticationException) fails.
            // GREEN: impl delegates to authManager → BadCredentialsException propagates → passes.
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
    // RED: stub throws UnsupportedOperationException
    // =========================================================

    @Nested
    @DisplayName("T9: login() calls JwtService.generateTokenPair() on success (AC3, AC6)")
    class DelegatesToJwtService {

        @Test
        @DisplayName("T9 [RED]: login() calls jwtService.generateTokenPair() and returns TokenPairResponse")
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

            // RED: stub throws UnsupportedOperationException  — none of the lines below are reached.
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
}
