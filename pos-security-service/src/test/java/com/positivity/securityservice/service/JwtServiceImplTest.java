package com.positivity.securityservice.service;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.entity.JwtToken;
import com.positivity.securityservice.internal.repository.JwtTokenRepository;
import com.positivity.securityservice.internal.service.JwtServiceImpl;
import com.positivity.securityservice.internal.service.RoleAuthorityServiceImpl;
import com.positivity.securityservice.internal.service.TokenRevocationManager;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class JwtServiceImplTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID TEST_USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private JwtTokenRepository jwtTokenRepository;
    @Mock
    private RoleAuthorityServiceImpl roleAuthorityService;
    @Mock
    private TokenRevocationManager tokenRevocationManager;

    @InjectMocks
    private JwtServiceImpl sut;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(
                sut,
                "jwtSecret",
                "this-is-a-long-test-secret-key-with-at-least-32-chars");
        ReflectionTestUtils.invokeMethod(sut, "initializeSecretKey");
        org.mockito.Mockito.lenient()
                .when(roleAuthorityService.expandRolesToAuthorities(any()))
                .thenReturn(Set.of("ROLE_ADMIN"));
        org.mockito.Mockito.lenient()
                .when(jwtTokenRepository.save(any(JwtToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("generateToken saves token and returns signed JWT")
    void generateToken_success() {
        String token = sut.generateToken("alice", TEST_USER_ID, Set.of("ADMIN"));

        assertThat(token).isNotBlank();
        verify(jwtTokenRepository).save(any(JwtToken.class));
    }

    @Test
    @DisplayName("validateToken returns true for stored non-revoked token")
    void validateToken_validStoredToken_returnsTrue() {
        String token = sut.generateToken("alice", TEST_USER_ID, Set.of("ADMIN"));
        JwtToken stored = new JwtToken();
        stored.setToken(token);
        stored.setRefreshToken("refresh");
        stored.setIssuedAt(Instant.now(TEST_CLOCK));
        stored.setExpiresAt(Instant.now(TEST_CLOCK).plusSeconds(300));
        stored.setRefreshExpiresAt(Instant.now(TEST_CLOCK).plusSeconds(600));
        stored.setSubject("alice");

        when(tokenRevocationManager.isRevoked(anyString())).thenReturn(false);
        when(jwtTokenRepository.findByToken(token)).thenReturn(Optional.of(stored));

        assertThat(sut.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("validateToken returns false for revoked token")
    void validateToken_revoked_returnsFalse() {
        String token = sut.generateToken("alice", TEST_USER_ID, Set.of("ADMIN"));
        when(tokenRevocationManager.isRevoked(anyString())).thenReturn(true);

        assertThat(sut.validateToken(token)).isFalse();
    }

    @Test
    @DisplayName("extractors return username, userId, roles, and authorities")
    void extractors_returnClaims() {
        String token = sut.generateToken("alice", TEST_USER_ID, Set.of("ADMIN"));

        assertThat(sut.getUsernameFromToken(token)).isEqualTo("alice");
        assertThat(sut.getUserIdFromToken(token)).isEqualTo(TEST_USER_ID);
        assertThat(sut.getRolesFromToken(token)).contains("ADMIN");
        assertThat(sut.getAuthoritiesFromToken(token)).contains("ROLE_ADMIN");
    }

    @Test
    @DisplayName("deleteToken removes from DB and revokes by JTI")
    void deleteToken_existingToken_revokes() {
        String token = sut.generateToken("alice", TEST_USER_ID, Set.of("ADMIN"));
        JwtToken stored = new JwtToken();
        stored.setToken(token);
        stored.setRefreshToken("refresh");
        stored.setIssuedAt(Instant.now(TEST_CLOCK));
        stored.setExpiresAt(Instant.now(TEST_CLOCK).plusSeconds(600));
        stored.setRefreshExpiresAt(Instant.now(TEST_CLOCK).plusSeconds(1200));
        stored.setSubject("alice");

        when(jwtTokenRepository.findByToken(token)).thenReturn(Optional.of(stored));
        doNothing().when(jwtTokenRepository).delete(stored);
        when(tokenRevocationManager.revokeToken(anyString(), anyLong())).thenReturn(true);

        assertThat(sut.deleteToken(token)).isTrue();
        verify(jwtTokenRepository).delete(stored);
        verify(tokenRevocationManager).revokeToken(anyString(), anyLong());
    }

    @Test
    @DisplayName("token pair flow validates and refreshes")
    void tokenPair_validateAndRefresh_success() {
        JwtService.TokenPair tokenPair = sut.generateTokenPair("alice", TEST_USER_ID, Set.of("ADMIN"));

        JwtToken stored = new JwtToken();
        stored.setToken(tokenPair.accessToken());
        stored.setRefreshToken(tokenPair.refreshToken());
        stored.setIssuedAt(Instant.now(TEST_CLOCK));
        stored.setExpiresAt(Instant.now(TEST_CLOCK).plusSeconds(600));
        stored.setRefreshExpiresAt(Instant.now(TEST_CLOCK).plusSeconds(1200));
        stored.setSubject("alice");

        when(tokenRevocationManager.isRevoked(anyString())).thenReturn(false);
        doReturn(Optional.of(stored))
                .doReturn(Optional.of(stored))
                .when(jwtTokenRepository)
                .findByRefreshToken(tokenPair.refreshToken());

        assertThat(sut.validateRefreshToken(tokenPair.refreshToken())).isTrue();

        JwtService.TokenPair refreshed = sut.refreshAccessToken(tokenPair.refreshToken());
        assertThat(refreshed.accessToken()).isNotBlank();
        assertThat(refreshed.refreshToken()).isNotBlank();
        verify(jwtTokenRepository).delete(stored);
    }

    @Test
    @DisplayName("initializeSecretKey: blank secret throws IllegalStateException")
    void initializeSecretKey_blankSecret_throws() {
        JwtServiceImpl fresh = new JwtServiceImpl(
                TEST_CLOCK, jwtTokenRepository, roleAuthorityService, tokenRevocationManager);
        ReflectionTestUtils.setField(fresh, "jwtSecret", "");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(fresh, "initializeSecretKey"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret must be provided");
    }

    @Test
    @DisplayName("initializeSecretKey: secret shorter than 32 bytes throws IllegalStateException")
    void initializeSecretKey_tooShortSecret_throws() {
        JwtServiceImpl fresh = new JwtServiceImpl(
                TEST_CLOCK, jwtTokenRepository, roleAuthorityService, tokenRevocationManager);
        ReflectionTestUtils.setField(fresh, "jwtSecret", "short");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(fresh, "initializeSecretKey"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters");
    }

    @Test
    @DisplayName("validateToken: returns false when token not found in database")
    void validateToken_notFoundInDb_returnsFalse() {
        String token = sut.generateToken("alice", TEST_USER_ID, Set.of("ADMIN"));
        when(tokenRevocationManager.isRevoked(anyString())).thenReturn(false);
        when(jwtTokenRepository.findByToken(token)).thenReturn(Optional.empty());

        assertThat(sut.validateToken(token)).isFalse();
    }

    @Test
    @DisplayName("validateToken: returns false for completely invalid token string")
    void validateToken_invalidTokenString_returnsFalse() {
        assertThat(sut.validateToken("not.a.jwt")).isFalse();
    }

    @Test
    @DisplayName("deleteToken: returns false when token not found in database")
    void deleteToken_notFound_returnsFalse() {
        String token = sut.generateToken("alice", TEST_USER_ID, Set.of("ADMIN"));
        when(jwtTokenRepository.findByToken(token)).thenReturn(Optional.empty());

        assertThat(sut.deleteToken(token)).isFalse();
        verify(tokenRevocationManager, never()).revokeToken(anyString(), anyLong());
    }

    @Test
    @DisplayName("generateTokenPair: blank username throws IllegalArgumentException")
    void generateTokenPair_blankUsername_throws() {
        assertThatThrownBy(() -> sut.generateTokenPair("", TEST_USER_ID, Set.of("ADMIN")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Username cannot be blank");
    }

    @Test
    @DisplayName("generateTokenPair: null userId throws IllegalArgumentException")
    void generateTokenPair_nullUserId_throws() {
        assertThatThrownBy(() -> sut.generateTokenPair("alice", null, Set.of("ADMIN")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UserId cannot be null");
    }

    @Test
    @DisplayName("generateTokenPair: empty roles throws IllegalArgumentException")
    void generateTokenPair_emptyRoles_throws() {
        assertThatThrownBy(() -> sut.generateTokenPair("alice", TEST_USER_ID, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Roles cannot be empty");
    }

    @Test
    @DisplayName("revokeTokenByJti: blank JTI throws IllegalArgumentException")
    void revokeTokenByJti_blankJti_throws() {
        assertThatThrownBy(() -> sut.revokeTokenByJti("", 3600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JTI cannot be blank");
    }

    @Test
    @DisplayName("revokeTokenByJti: non-positive expiration throws IllegalArgumentException")
    void revokeTokenByJti_nonPositiveExpiration_throws() {
        assertThatThrownBy(() -> sut.revokeTokenByJti("some-jti", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expiration seconds must be positive");
    }

    @Test
    @DisplayName("revokeTokenByJti: valid args delegates to TokenRevocationManager")
    void revokeTokenByJti_success() {
        when(tokenRevocationManager.revokeToken("some-jti", 3600)).thenReturn(true);

        sut.revokeTokenByJti("some-jti", 3600);

        verify(tokenRevocationManager).revokeToken("some-jti", 3600);
    }

    @Test
    @DisplayName("validateRefreshToken: returns false when not found in database")
    void validateRefreshToken_notFoundInDb_returnsFalse() {
        JwtService.TokenPair pair = sut.generateTokenPair("alice", TEST_USER_ID, Set.of("ADMIN"));
        when(tokenRevocationManager.isRevoked(anyString())).thenReturn(false);
        when(jwtTokenRepository.findByRefreshToken(pair.refreshToken())).thenReturn(Optional.empty());

        assertThat(sut.validateRefreshToken(pair.refreshToken())).isFalse();
    }

    @Test
    @DisplayName("validateRefreshToken: returns false when revoked")
    void validateRefreshToken_revoked_returnsFalse() {
        JwtService.TokenPair pair = sut.generateTokenPair("alice", TEST_USER_ID, Set.of("ADMIN"));
        when(tokenRevocationManager.isRevoked(anyString())).thenReturn(true);

        assertThat(sut.validateRefreshToken(pair.refreshToken())).isFalse();
    }

    @Test
    @DisplayName("refreshAccessToken: invalid refresh token throws IllegalArgumentException")
    void refreshAccessToken_invalidRefreshToken_throws() {
        assertThatThrownBy(() -> sut.refreshAccessToken("not.a.real.token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid refresh token");
    }
}
