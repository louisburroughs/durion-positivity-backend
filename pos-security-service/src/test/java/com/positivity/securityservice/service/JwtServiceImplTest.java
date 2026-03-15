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

import com.positivity.securityservice.internal.domain.PermissionBitsetCodec;
import com.positivity.securityservice.internal.dto.UserDto;
import com.positivity.securityservice.internal.entity.JwtToken;
import com.positivity.securityservice.internal.enums.PermissionCode;
import com.positivity.securityservice.internal.exception.InvalidRefreshTokenException;
import com.positivity.securityservice.internal.repository.JwtTokenRepository;
import com.positivity.securityservice.internal.service.JwtServiceImpl;
import com.positivity.securityservice.internal.service.RoleAuthorityServiceImpl;
import com.positivity.securityservice.internal.service.TokenRevocationManager;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
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
    private UserService userService;
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
        org.mockito.Mockito.lenient()
                .when(userService.getUserById(any(UUID.class)))
                .thenReturn(Optional.of(UserDto.builder()
                        .id(TEST_USER_ID)
                        .username("alice")
                        .roles(Set.of("ADMIN"))
                        .build()));
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
    @DisplayName("extractors return username and userId")
    void extractors_returnClaims() {
        String token = sut.generateToken("alice", TEST_USER_ID, Set.of("ADMIN"));

        assertThat(sut.getUsernameFromToken(token)).isEqualTo("alice");
        assertThat(sut.getUserIdFromToken(token)).isEqualTo(TEST_USER_ID);
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
        JwtService.TokenPair tokenPair = sut.generateTokenPair("alice", TEST_USER_ID, null, Set.of("ADMIN"));

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
                TEST_CLOCK, jwtTokenRepository, roleAuthorityService, userService, tokenRevocationManager);
        ReflectionTestUtils.setField(fresh, "jwtSecret", "");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(fresh, "initializeSecretKey"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret must be provided");
    }

    @Test
    @DisplayName("initializeSecretKey: secret shorter than 32 bytes throws IllegalStateException")
    void initializeSecretKey_tooShortSecret_throws() {
        JwtServiceImpl fresh = new JwtServiceImpl(
                TEST_CLOCK, jwtTokenRepository, roleAuthorityService, userService, tokenRevocationManager);
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
        Set<String> roles = Set.of("ADMIN");
        assertThatThrownBy(() -> sut.generateTokenPair("", TEST_USER_ID, null, roles))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Username cannot be blank");
    }

    @Test
    @DisplayName("generateTokenPair: null userId throws IllegalArgumentException")
    void generateTokenPair_nullUserId_throws() {
        Set<String> roles = Set.of("ADMIN");
        assertThatThrownBy(() -> sut.generateTokenPair("alice", null, null, roles))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UserId cannot be null");
    }

    @Test
    @DisplayName("generateTokenPair: empty roles throws IllegalArgumentException")
    void generateTokenPair_emptyRoles_throws() {
        Set<String> roles = Set.of();
        assertThatThrownBy(() -> sut.generateTokenPair("alice", TEST_USER_ID, null, roles))
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
        JwtService.TokenPair pair = sut.generateTokenPair("alice", TEST_USER_ID, null, Set.of("ADMIN"));
        when(tokenRevocationManager.isRevoked(anyString())).thenReturn(false);
        when(jwtTokenRepository.findByRefreshToken(pair.refreshToken())).thenReturn(Optional.empty());

        assertThat(sut.validateRefreshToken(pair.refreshToken())).isFalse();
    }

    @Test
    @DisplayName("validateRefreshToken: returns false when revoked")
    void validateRefreshToken_revoked_returnsFalse() {
        JwtService.TokenPair pair = sut.generateTokenPair("alice", TEST_USER_ID, null, Set.of("ADMIN"));
        when(tokenRevocationManager.isRevoked(anyString())).thenReturn(true);

        assertThat(sut.validateRefreshToken(pair.refreshToken())).isFalse();
    }

    @Test
    @DisplayName("refresh token includes expected issuer and audience claims")
    void refreshToken_containsExpectedIssuerAndAudience() {
        JwtService.TokenPair pair = sut.generateTokenPair("alice", TEST_USER_ID, null, Set.of("ADMIN"));

        SecretKey key = (SecretKey) ReflectionTestUtils.getField(sut, "secretKey");
        Claims claims = Jwts.parser().verifyWith(key)
                .clock(() -> Date.from(Instant.now(TEST_CLOCK)))
                .build()
                .parseSignedClaims(pair.refreshToken())
                .getPayload();

        assertThat(claims.getIssuer()).isEqualTo("pos-security-service");
        assertThat(claims.getAudience()).containsExactly("api-gateway");
    }

    @Test
    @DisplayName("validateRefreshToken: returns false when issuer claim is incorrect")
    void validateRefreshToken_wrongIssuer_returnsFalse() {
        SecretKey key = (SecretKey) ReflectionTestUtils.getField(sut, "secretKey");
        String refreshTokenWithWrongIssuer = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject("alice")
                .issuer("wrong-issuer")
                .audience().add("api-gateway").and()
                .claim(JwtService.UID, TEST_USER_ID.toString())
                .claim("type", "refresh")
                .issuedAt(Date.from(Instant.now(TEST_CLOCK)))
                .expiration(Date.from(Instant.now(TEST_CLOCK).plusSeconds(3600)))
                .signWith(key)
                .compact();

        assertThat(sut.validateRefreshToken(refreshTokenWithWrongIssuer)).isFalse();
    }

    @Test
    @DisplayName("validateRefreshToken: returns false when audience claim is missing")
    void validateRefreshToken_missingAudience_returnsFalse() {
        SecretKey key = (SecretKey) ReflectionTestUtils.getField(sut, "secretKey");
        String refreshTokenWithMissingAudience = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject("alice")
                .issuer("pos-security-service")
                .claim(JwtService.UID, TEST_USER_ID.toString())
                .claim("type", "refresh")
                .issuedAt(Date.from(Instant.now(TEST_CLOCK)))
                .expiration(Date.from(Instant.now(TEST_CLOCK).plusSeconds(3600)))
                .signWith(key)
                .compact();

        assertThat(sut.validateRefreshToken(refreshTokenWithMissingAudience)).isFalse();
    }

    @Test
    @DisplayName("refreshAccessToken: invalid refresh token throws IllegalArgumentException")
    void refreshAccessToken_invalidRefreshToken_throws() {
        assertThatThrownBy(() -> sut.refreshAccessToken("not.a.real.token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid refresh token");
    }

    @Test
    @DisplayName("refreshAccessToken throws InvalidRefreshTokenException when user no longer exists")
    void refreshAccessToken_throwsInvalidRefreshTokenException_whenUserNotFound() {
        UUID userId = UUID.randomUUID();
        JwtService.TokenPair tokenPair = sut.generateTokenPair(userId.toString(), userId, null, Set.of("ADMIN"));
        String refreshToken = tokenPair.refreshToken();

        JwtToken stored = new JwtToken();
        stored.setToken(tokenPair.accessToken());
        stored.setRefreshToken(tokenPair.refreshToken());
        stored.setIssuedAt(Instant.now(TEST_CLOCK));
        stored.setExpiresAt(Instant.now(TEST_CLOCK).plusSeconds(600));
        stored.setRefreshExpiresAt(Instant.now(TEST_CLOCK).plusSeconds(1200));
        stored.setSubject(userId.toString());

        when(tokenRevocationManager.isRevoked(anyString())).thenReturn(false);
        doReturn(Optional.of(stored))
                .doReturn(Optional.of(stored))
                .when(jwtTokenRepository)
                .findByRefreshToken(refreshToken);
        when(userService.getUserById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.refreshAccessToken(refreshToken))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("no longer exists");
    }

    @Test
    @DisplayName("getUserIdFromToken falls back correctly to legacy userId claim")
    void getUserIdFromToken_fallsBackToLegacyUserIdClaim() {
        UUID legacyUserId = UUID.randomUUID();
        SecretKey key = (SecretKey) ReflectionTestUtils.getField(sut, "secretKey");

        String legacyToken = Jwts.builder()
                .subject("alice")
                .issuer("pos-security-service")
                .audience().add("api-gateway").and()
                .claim(JwtService.USER_ID, legacyUserId.toString())
                .issuedAt(Date.from(Instant.now(TEST_CLOCK)))
                .expiration(Date.from(Instant.now(TEST_CLOCK).plusSeconds(3600)))
                .signWith(key)
                .compact();

        assertThat(sut.getUserIdFromToken(legacyToken)).isEqualTo(legacyUserId);
    }

    // Issue #PERM-004 start: JWT claim structure tests for compact permission
    // bitset encoding

    /**
     * Verifies that the access token produced by generateTokenPair contains
     * the {@code perm_bits} Base64URL bitset claim and the {@code perm_ver}
     * catalog-version integer claim required by the PERM-004 contract.
     *
     * Issue: PERM-004
     */
    @Test
    @DisplayName("generateTokenPair access token must include perm_bits and perm_ver claims")
    void accessToken_containsPermBitsAndPermVer() {
        when(roleAuthorityService.expandRolesToAuthorities(any()))
                .thenReturn(Set.of(
                        PermissionCode.ACCOUNTING__JE__VIEW.code(),
                        PermissionCode.ACCOUNTING__JE__CREATE.code()));

        JwtService.TokenPair tokenPair = sut.generateTokenPair("alice", UUID.randomUUID(), null, Set.of("USER"));

        SecretKey key = (SecretKey) ReflectionTestUtils.getField(sut, "secretKey");
        Claims claims = Jwts.parser().verifyWith(key)
                .clock(() -> Date.from(Instant.now(TEST_CLOCK)))
                .build()
                .parseSignedClaims(tokenPair.accessToken()).getPayload();

        assertThat(claims).containsKey(JwtService.PERM_BITS);
        assertThat(claims.get(JwtService.PERM_VER, Integer.class)).isEqualTo(PermissionCode.CATALOG_VERSION);

        String permBits = claims.get(JwtService.PERM_BITS, String.class);
        Set<PermissionCode> decoded = PermissionBitsetCodec.decodeToPermissions(permBits,
                PermissionCode.CATALOG_VERSION);
        assertThat(decoded).contains(
                PermissionCode.ACCOUNTING__JE__VIEW,
                PermissionCode.ACCOUNTING__JE__CREATE);
    }

    /**
     * Verifies that the access token produced by generateTokenPair does NOT
     * contain the legacy {@code roles} or {@code authorities} list claims.
     *
     * Issue: PERM-004
     */
    @Test
    @DisplayName("generateTokenPair access token must NOT include roles or authorities claims")
    void accessToken_doesNotContainRolesOrAuthorities() {
        JwtService.TokenPair tokenPair = sut.generateTokenPair("alice", UUID.randomUUID(), null, Set.of("ADMIN"));

        SecretKey key = (SecretKey) ReflectionTestUtils.getField(sut, "secretKey");
        Claims claims = Jwts.parser().verifyWith(key)
                .clock(() -> Date.from(Instant.now(TEST_CLOCK)))
                .build()
                .parseSignedClaims(tokenPair.accessToken()).getPayload();

        assertThat(claims.containsKey(JwtService.ROLES)).isFalse();
        assertThat(claims.containsKey(JwtService.AUTHORITIES)).isFalse();
    }

    @Test
    @DisplayName("generateTokenPair refresh token must NOT include roles or perm_bits claims")
    void refreshToken_doesNotContainRolesOrPermBits() {
        JwtService.TokenPair tokenPair = sut.generateTokenPair("alice", UUID.randomUUID(), null, Set.of("ADMIN"));

        SecretKey key = (SecretKey) ReflectionTestUtils.getField(sut, "secretKey");
        Claims claims = Jwts.parser().verifyWith(key)
                .clock(() -> Date.from(Instant.now(TEST_CLOCK)))
                .build()
                .parseSignedClaims(tokenPair.refreshToken()).getPayload();

        assertThat(claims.containsKey(JwtService.ROLES)).isFalse();
        assertThat(claims.containsKey(JwtService.AUTHORITIES)).isFalse();
        assertThat(claims.containsKey(JwtService.PERM_BITS)).isFalse();
        assertThat(claims).containsKey(JwtService.UID).containsEntry("type", "refresh");
    }

    /**
     * Verifies that the access token contains the new {@code uid} UUID claim
     * and the {@code username} display-name claim.
     *
     * Issue: PERM-004
     */
    @Test
    @DisplayName("generateTokenPair access token must include uid and username claims")
    void accessToken_containsUidAndUsername() {
        UUID userId = UUID.randomUUID();

        JwtService.TokenPair tokenPair = sut.generateTokenPair("alice", userId, null, Set.of("ADMIN"));

        SecretKey key = (SecretKey) ReflectionTestUtils.getField(sut, "secretKey");
        Claims claims = Jwts.parser().verifyWith(key)
                .clock(() -> Date.from(Instant.now(TEST_CLOCK)))
                .build()
                .parseSignedClaims(tokenPair.accessToken()).getPayload();

        assertThat(claims.get(JwtService.UID, String.class)).isEqualTo(userId.toString());
        assertThat(claims.get(JwtService.USERNAME, String.class)).isEqualTo("alice");
    }

    /**
     * Verifies that getUserIdFromToken correctly extracts the UUID from a
     * token generated by the new claim structure.
     *
     * Issue: PERM-004
     */
    @Test
    @DisplayName("getUserIdFromToken returns correct UUID from new uid claim format")
    void getUserIdFromToken_readsUidClaim() {
        UUID userId = UUID.randomUUID();

        JwtService.TokenPair tokenPair = sut.generateTokenPair("alice", userId, null, Set.of("ADMIN"));

        assertThat(sut.getUserIdFromToken(tokenPair.accessToken())).isEqualTo(userId);
    }

    /**
     * Verifies that getAuthoritiesFromToken decodes permission codes from
     * the {@code perm_bits} claim when it is present in the token.
     *
     * Issue: PERM-004
     */
    @Test
    @DisplayName("getAuthoritiesFromToken decodes perm_bits when present in token")
    void getAuthoritiesFromToken_decodesPermBitsWhenPresent() {
        when(roleAuthorityService.expandRolesToAuthorities(any()))
                .thenReturn(Set.of(PermissionCode.ACCOUNTING__JE__VIEW.code()));

        JwtService.TokenPair tokenPair = sut.generateTokenPair("alice", UUID.randomUUID(), null, Set.of("USER"));

        SecretKey key = (SecretKey) ReflectionTestUtils.getField(sut, "secretKey");
        Claims claims = Jwts.parser().verifyWith(key)
                .clock(() -> Date.from(Instant.now(TEST_CLOCK)))
                .build()
                .parseSignedClaims(tokenPair.accessToken()).getPayload();
        // perm_bits must be present for the decode path to be exercised
        assertThat(claims).containsKey(JwtService.PERM_BITS);

        Set<String> authorities = sut.getAuthoritiesFromToken(tokenPair.accessToken());
        assertThat(authorities).contains(PermissionCode.ACCOUNTING__JE__VIEW.code());
    }

    /**
     * Verifies backward-compatibility: tokens that carry a legacy
     * {@code authorities} list claim (but no {@code perm_bits} claim) are
     * still decoded correctly by getAuthoritiesFromToken.
     *
     * Issue: PERM-004
     */
    @Test
    @DisplayName("getAuthoritiesFromToken falls back to legacy authorities claim for old tokens")
    void getAuthoritiesFromToken_fallsBackToLegacyClaimForOldTokens() {
        SecretKey key = (SecretKey) ReflectionTestUtils.getField(sut, "secretKey");
        String legacyToken = Jwts.builder()
                .subject("alice")
                .issuer("pos-security-service")
                .audience().add("api-gateway").and()
                .claim(JwtService.AUTHORITIES, List.of(PermissionCode.ACCOUNTING__JE__VIEW.code()))
                .issuedAt(Date.from(Instant.now(TEST_CLOCK)))
                .expiration(Date.from(Instant.now(TEST_CLOCK).plusSeconds(3600)))
                .signWith(key)
                .compact();

        Set<String> authorities = sut.getAuthoritiesFromToken(legacyToken);

        assertThat(authorities).contains(PermissionCode.ACCOUNTING__JE__VIEW.code());
    }

    /**
     * Verifies that the access token remains compact: for 100 permission codes
     * encoded as a bitset, the total JWT byte length must stay below 600 bytes.
     *
     * Issue: PERM-004
     */
    @Test
    @DisplayName("access token is less than 600 bytes for 100 permission codes")
    void accessToken_lessThan600BytesForHundredPermissions() {
        List<PermissionCode> first100 = Arrays.asList(PermissionCode.values()).subList(0, 100);
        Set<String> permCodeStrings = first100.stream()
                .map(PermissionCode::code)
                .collect(Collectors.toSet());

        when(roleAuthorityService.expandRolesToAuthorities(any())).thenReturn(permCodeStrings);

        JwtService.TokenPair tokenPair = sut.generateTokenPair("alice", UUID.randomUUID(), null, Set.of("USER"));

        assertThat(tokenPair.accessToken().getBytes(StandardCharsets.UTF_8))
                .hasSizeLessThan(600);
    }

    /**
     * Verifies that getAuthoritiesFromToken falls through to Tier 3 (role-expansion
     * via roleAuthorityService) when the token contains neither {@code perm_bits}
     * nor {@code authorities} claims. Also exercises getRolesFromToken's loop that
     * builds a Set from a {@code roles} list claim.
     *
     * Issue: PERM-004
     */
    @Test
    @DisplayName("getAuthoritiesFromToken tier-3: no perm_bits or authorities → expands roles via service")
    void getAuthoritiesFromToken_tier3_noPermBitsNoAuthorities_usesRoleExpansion() {
        SecretKey key = (SecretKey) ReflectionTestUtils.getField(sut, "secretKey");
        String legacyToken = Jwts.builder()
                .subject("alice")
                .issuer("pos-security-service")
                .audience().add("api-gateway").and()
                .claim(JwtService.ROLES, List.of("ADMIN"))
                .issuedAt(Date.from(Instant.now(TEST_CLOCK)))
                .expiration(Date.from(Instant.now(TEST_CLOCK).plusSeconds(3600)))
                .signWith(key)
                .compact();

        when(roleAuthorityService.expandRolesToAuthorities(Set.of("ADMIN")))
                .thenReturn(Set.of("ORDER:VIEW"));

        Set<String> authorities = sut.getAuthoritiesFromToken(legacyToken);

        assertThat(authorities).containsExactly("ORDER:VIEW");
        verify(roleAuthorityService).expandRolesToAuthorities(Set.of("ADMIN"));
    }

    /**
     * Verifies that getAuthoritiesFromToken falls back to {@code CATALOG_VERSION}
     * when the {@code perm_ver} claim is present but is not a Number (e.g., a
     * String). The bitset must still decode correctly using the default version.
     *
     * Issue: PERM-004
     */
    @Test
    @DisplayName("getAuthoritiesFromToken: non-Number perm_ver falls back to CATALOG_VERSION default")
    void getAuthoritiesFromToken_nonNumberPermVer_usesDefaultCatalogVersion() {
        SecretKey key = (SecretKey) ReflectionTestUtils.getField(sut, "secretKey");
        Set<PermissionCode> permCodes = Set.of(PermissionCode.ACCOUNTING__JE__VIEW);
        String permBits = PermissionBitsetCodec.encode(permCodes);

        String tokenWithStringPermVer = Jwts.builder()
                .subject("alice")
                .issuer("pos-security-service")
                .audience().add("api-gateway").and()
                .claim(JwtService.PERM_BITS, permBits)
                .claim(JwtService.PERM_VER, "unknown-version")
                .issuedAt(Date.from(Instant.now(TEST_CLOCK)))
                .expiration(Date.from(Instant.now(TEST_CLOCK).plusSeconds(3600)))
                .signWith(key)
                .compact();

        Set<String> authorities = sut.getAuthoritiesFromToken(tokenWithStringPermVer);

        assertThat(authorities).contains(PermissionCode.ACCOUNTING__JE__VIEW.code());
    }

    /**
     * Verifies that refreshAccessToken throws IllegalArgumentException when the
     * user referenced by the refresh token exists but has no roles assigned.
     *
     * Issue: PERM-004
     */
    @Test
    @DisplayName("refreshAccessToken throws IllegalArgumentException when user has no roles assigned")
    void refreshAccessToken_userHasNoRoles_throwsIllegalArgumentException() {
        UUID userId = UUID.randomUUID();
        JwtService.TokenPair tokenPair = sut.generateTokenPair(userId.toString(), userId, null, Set.of("ADMIN"));
        String refreshToken = tokenPair.refreshToken();

        JwtToken stored = new JwtToken();
        stored.setToken(tokenPair.accessToken());
        stored.setRefreshToken(refreshToken);
        stored.setIssuedAt(Instant.now(TEST_CLOCK));
        stored.setExpiresAt(Instant.now(TEST_CLOCK).plusSeconds(600));
        stored.setRefreshExpiresAt(Instant.now(TEST_CLOCK).plusSeconds(1200));
        stored.setSubject(userId.toString());

        when(tokenRevocationManager.isRevoked(anyString())).thenReturn(false);
        doReturn(Optional.of(stored))
                .doReturn(Optional.of(stored))
                .when(jwtTokenRepository)
                .findByRefreshToken(refreshToken);
        when(userService.getUserById(userId)).thenReturn(Optional.of(UserDto.builder()
                .id(userId)
                .username(userId.toString())
                .roles(Set.of())
                .build()));

        assertThatThrownBy(() -> sut.refreshAccessToken(refreshToken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no roles assigned");
    }

    /**
     * Verifies that getUserIdFromToken returns null when neither the {@code uid}
     * nor the legacy {@code userId} claim is present in the token.
     *
     * Issue: PERM-004
     */
    @Test
    @DisplayName("getUserIdFromToken returns null when neither uid nor userId claim is present")
    void getUserIdFromToken_returnsNullWhenNoUidOrUserIdClaim() {
        SecretKey key = (SecretKey) ReflectionTestUtils.getField(sut, "secretKey");
        String tokenWithoutUid = Jwts.builder()
                .subject("alice")
                .issuer("pos-security-service")
                .audience().add("api-gateway").and()
                .issuedAt(Date.from(Instant.now(TEST_CLOCK)))
                .expiration(Date.from(Instant.now(TEST_CLOCK).plusSeconds(3600)))
                .signWith(key)
                .compact();

        assertThat(sut.getUserIdFromToken(tokenWithoutUid)).isNull();
    }

    // Issue #PERM-004 end

    // =========================================================
    // AUTH-005: personId claim in access token
    // =========================================================

    @org.junit.jupiter.api.Nested
    @DisplayName("AUTH-005: personId claim contract")
    class PersonIdClaim {

        @Test
        @DisplayName("access token contains personId claim when personId is non-null")
        void accessToken_containsPersonIdClaim_whenNonNull() {
            UUID personId = UUID.fromString("22222222-2222-2222-2222-222222222222");

            // generateTokenPair must accept personId as 3rd parameter (UUID, nullable)
            JwtService.TokenPair pair = sut.generateTokenPair("alice", TEST_USER_ID, personId, Set.of("ADMIN"));

            UUID extractedPersonId = sut.getPersonIdFromToken(pair.accessToken());
            assertThat(extractedPersonId).isEqualTo(personId);
        }

        @Test
        @DisplayName("access token omits personId claim when personId is null")
        void accessToken_omitsPersonIdClaim_whenNull() {
            JwtService.TokenPair pair = sut.generateTokenPair("alice", TEST_USER_ID, null, Set.of("ADMIN"));

            UUID extractedPersonId = sut.getPersonIdFromToken(pair.accessToken());
            assertThat(extractedPersonId).isNull();
        }

        @Test
        @DisplayName("refresh token never contains personId claim even when personId is non-null")
        void refreshToken_neverContainsPersonIdClaim() {
            UUID personId = UUID.fromString("44444444-4444-4444-4444-444444444444");

            JwtService.TokenPair pair = sut.generateTokenPair("alice", TEST_USER_ID, personId, Set.of("ADMIN"));

            UUID extractedFromRefresh = sut.getPersonIdFromToken(pair.refreshToken());
            assertThat(extractedFromRefresh).isNull();
        }

        @Test
        @DisplayName("getPersonIdFromToken returns null when personId claim contains a malformed UUID")
        void getPersonIdFromToken_malformedUuid_returnsNull() {
            SecretKey key = (SecretKey) ReflectionTestUtils.getField(sut, "secretKey");
            String tokenWithMalformedPersonId = Jwts.builder()
                    .subject("alice")
                    .issuer("pos-security-service")
                    .audience().add("api-gateway").and()
                    .claim(JwtService.PERSON_ID, "not-a-valid-uuid")
                    .issuedAt(Date.from(Instant.now(TEST_CLOCK)))
                    .expiration(Date.from(Instant.now(TEST_CLOCK).plusSeconds(3600)))
                    .signWith(key)
                    .compact();

            UUID personId = sut.getPersonIdFromToken(tokenWithMalformedPersonId);
            assertThat(personId).isNull();
        }

        @Test
        @DisplayName("refreshAccessToken forwards personId from user record into the new access token")
        void refreshAccessToken_forwardsPersonIdFromUser_intoNewAccessToken() {
            UUID personId = UUID.fromString("33333333-3333-3333-3333-333333333333");
            UUID userId = UUID.randomUUID();
            JwtService.TokenPair tokenPair = sut.generateTokenPair(userId.toString(), userId, null, Set.of("ADMIN"));
            String refreshToken = tokenPair.refreshToken();

            JwtToken stored = new JwtToken();
            stored.setToken(tokenPair.accessToken());
            stored.setRefreshToken(refreshToken);
            stored.setIssuedAt(Instant.now(TEST_CLOCK));
            stored.setExpiresAt(Instant.now(TEST_CLOCK).plusSeconds(600));
            stored.setRefreshExpiresAt(Instant.now(TEST_CLOCK).plusSeconds(1200));
            stored.setSubject(userId.toString());

            when(tokenRevocationManager.isRevoked(anyString())).thenReturn(false);
            doReturn(Optional.of(stored))
                    .doReturn(Optional.of(stored))
                    .when(jwtTokenRepository)
                    .findByRefreshToken(refreshToken);
            when(userService.getUserById(userId)).thenReturn(Optional.of(UserDto.builder()
                    .id(userId)
                    .username(userId.toString())
                    .roles(Set.of("ADMIN"))
                    .personId(personId)
                    .build()));

            JwtService.TokenPair refreshed = sut.refreshAccessToken(refreshToken);

            UUID extractedPersonId = sut.getPersonIdFromToken(refreshed.accessToken());
            assertThat(extractedPersonId).isEqualTo(personId);
        }
    }
}
