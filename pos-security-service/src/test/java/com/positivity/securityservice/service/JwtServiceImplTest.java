package com.positivity.securityservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.entity.JwtToken;
import com.positivity.securityservice.internal.repository.JwtTokenRepository;
import com.positivity.securityservice.internal.service.JwtServiceImpl;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class JwtServiceImplTest {

    @Mock
    private JwtTokenRepository jwtTokenRepository;
    @Mock
    private RoleAuthorityService roleAuthorityService;
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
        when(roleAuthorityService.expandRolesToAuthorities(any())).thenReturn(Set.of("ROLE_ADMIN"));
        when(jwtTokenRepository.save(any(JwtToken.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("generateToken saves token and returns signed JWT")
    void generateToken_success() {
        String token = sut.generateToken("alice", "person-1", Set.of("ADMIN"));

        assertThat(token).isNotBlank();
        verify(jwtTokenRepository).save(any(JwtToken.class));
    }

    @Test
    @DisplayName("validateToken returns true for stored non-revoked token")
    void validateToken_validStoredToken_returnsTrue() {
        String token = sut.generateToken("alice", "person-1", Set.of("ADMIN"));
        JwtToken stored = new JwtToken();
        stored.setToken(token);
        stored.setRefreshToken("refresh");
        stored.setIssuedAt(Instant.now());
        stored.setExpiresAt(Instant.now().plusSeconds(300));
        stored.setRefreshExpiresAt(Instant.now().plusSeconds(600));
        stored.setSubject("alice");

        when(tokenRevocationManager.isRevoked(anyString())).thenReturn(false);
        when(jwtTokenRepository.findByToken(token)).thenReturn(Optional.of(stored));

        assertThat(sut.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("validateToken returns false for revoked token")
    void validateToken_revoked_returnsFalse() {
        String token = sut.generateToken("alice", "person-1", Set.of("ADMIN"));
        when(tokenRevocationManager.isRevoked(anyString())).thenReturn(true);

        assertThat(sut.validateToken(token)).isFalse();
    }

    @Test
    @DisplayName("extractors return username, personId, roles, and authorities")
    void extractors_returnClaims() {
        String token = sut.generateToken("alice", "person-1", Set.of("ADMIN"));

        assertThat(sut.getUsernameFromToken(token)).isEqualTo("alice");
        assertThat(sut.getPersonIdFromToken(token)).isEqualTo("person-1");
        assertThat(sut.getRolesFromToken(token)).contains("ADMIN");
        assertThat(sut.getAuthoritiesFromToken(token)).contains("ROLE_ADMIN");
    }

    @Test
    @DisplayName("deleteToken removes from DB and revokes by JTI")
    void deleteToken_existingToken_revokes() {
        String token = sut.generateToken("alice", "person-1", Set.of("ADMIN"));
        JwtToken stored = new JwtToken();
        stored.setToken(token);
        stored.setRefreshToken("refresh");
        stored.setIssuedAt(Instant.now());
        stored.setExpiresAt(Instant.now().plusSeconds(600));
        stored.setRefreshExpiresAt(Instant.now().plusSeconds(1200));
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
        JwtService.TokenPair tokenPair = sut.generateTokenPair("alice", "person-1", Set.of("ADMIN"));

        JwtToken stored = new JwtToken();
        stored.setToken(tokenPair.accessToken());
        stored.setRefreshToken(tokenPair.refreshToken());
        stored.setIssuedAt(Instant.now());
        stored.setExpiresAt(Instant.now().plusSeconds(600));
        stored.setRefreshExpiresAt(Instant.now().plusSeconds(1200));
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
}