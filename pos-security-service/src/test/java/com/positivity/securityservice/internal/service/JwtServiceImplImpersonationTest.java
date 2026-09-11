package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.domain.PermissionBitsetCodec;
import com.positivity.securityservice.internal.entity.JwtToken;
import com.positivity.securityservice.internal.enums.PermissionCode;
import com.positivity.securityservice.internal.exception.InvalidRefreshTokenException;
import com.positivity.securityservice.internal.exception.SecurityValidationException;
import com.positivity.securityservice.internal.repository.JwtTokenRepository;
import com.positivity.securityservice.internal.security.service.JwtService;
import com.positivity.securityservice.internal.security.service.JwtService.IssuedImpersonationToken;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantResolver;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The impersonation token {@link JwtServiceImpl} mints (ADR-0062 §7, WS2b-4): its claim shape,
 * its 15-minute lifetime, the absence of a refresh half, and the refresh path's refusal of it.
 */
@ExtendWith(MockitoExtension.class)
class JwtServiceImplImpersonationTest {

    private static final String SECRET = "this-is-a-long-test-secret-key-with-at-least-32-chars";
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final Clock TEST_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID DEFAULT_TENANT = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID TARGET_TENANT = UUID.fromString("01990000-0000-7000-8000-000000000123");
    private static final UUID OPERATOR_ID = UUID.fromString("01900000-0000-7000-8000-0000000a0101");
    private static final String SUBJECT = "support:admin.platform@acme";

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

    @Mock
    private UserDetailsService userDetailsService;

    @Mock
    private StaffingAssignmentProjectionService staffingAssignmentProjectionService;

    @Spy
    private TenantResolver tenantResolver = tenantResolver();

    @InjectMocks
    private JwtServiceImpl sut;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(sut, "jwtSecret", SECRET);
        ReflectionTestUtils.invokeMethod(sut, "initializeSecretKey");
        lenient()
                .when(roleAuthorityService.expandRolesToAuthorities(Set.of("SUPPORT")))
                .thenReturn(Set.of("ROLE_SUPPORT", "crm:party:view", "order:order:view", "not-a-catalog-code"));
        lenient().when(jwtTokenRepository.save(any(JwtToken.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private IssuedImpersonationToken mintUnderTarget() {
        return TenantContext.callAs(
                TARGET_TENANT,
                () -> sut.generateImpersonationToken(SUBJECT, OPERATOR_ID, "admin.platform", Set.of("SUPPORT")));
    }

    private static Claims claims(String token) {
        SecretKey key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(NOW))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    @Test
    @DisplayName("claim shape: tid = the bound target tenant, act = the operator, token_use = impersonation")
    void claimShape() {
        IssuedImpersonationToken issued = mintUnderTarget();
        Claims claims = claims(issued.token());

        assertThat(claims.getSubject()).isEqualTo(SUBJECT);
        assertThat(claims.getIssuer()).isEqualTo("pos-security-service");
        assertThat(claims.getAudience()).containsExactly("api-gateway");
        assertThat(claims.getId()).isEqualTo(issued.jti()).isNotBlank();
        assertThat(claims.get(JwtService.TID, String.class)).isEqualTo(TARGET_TENANT.toString());
        assertThat(claims.get(JwtService.UID, String.class)).isEqualTo(OPERATOR_ID.toString());
        assertThat(claims.get(JwtService.USERNAME, String.class)).isEqualTo(SUBJECT);
        assertThat(claims.get(JwtService.TOKEN_USE, String.class)).isEqualTo(JwtService.TOKEN_USE_IMPERSONATION);
        assertThat(claims.get(JwtService.ACT))
                .isInstanceOf(Map.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsExactly(Map.entry("sub", OPERATOR_ID.toString()), Map.entry("username", "admin.platform"));
        assertThat(claims.get(JwtService.ROLES)).isEqualTo(List.of("ROLE_SUPPORT"));
        assertThat(claims.get(JwtService.PERM_VER, Integer.class)).isEqualTo(PermissionCode.CATALOG_VERSION);
        assertThat(claims.get(JwtService.PERSON_ID)).isNull();
        assertThat(claims.get(JwtService.LOC_SCOPE)).isNull();
        assertThat(claims.get("type")).isNull();
    }

    @Test
    @DisplayName("perm_bits is the SUPPORT role's grants resolved under the target binding; scope bitsets are empty")
    void permBitsAreTheSupportGrants() {
        String token = mintUnderTarget().token();
        Claims claims = claims(token);

        assertThat(PermissionBitsetCodec.decodeToPermissions(
                        claims.get(JwtService.PERM_BITS, String.class), PermissionCode.CATALOG_VERSION))
                .containsExactlyInAnyOrder(PermissionCode.CRM__PARTY__VIEW, PermissionCode.ORDER__ORDER__VIEW);
        assertThat(sut.getAuthoritiesFromToken(token)).containsExactlyInAnyOrder("crm:party:view", "order:order:view");
        assertThat(sut.getFinancialLocationScopedPermissionsFromToken(token)).isEmpty();
        assertThat(sut.getOtherLocationScopedPermissionsFromToken(token)).isEmpty();
        assertThat(sut.getLocationScopeFromToken(token)).isEmpty();
        assertThat(sut.getRolesFromToken(token)).containsExactly("ROLE_SUPPORT");
        assertThat(sut.getUserIdFromToken(token)).isEqualTo(OPERATOR_ID);
        verify(staffingAssignmentProjectionService, never()).assignedLocationIds(any(), any());
    }

    @Test
    @DisplayName("expires exactly 15 minutes after minting, and is stored without a refresh half")
    void fifteenMinutesNoRefresh() {
        IssuedImpersonationToken issued = mintUnderTarget();

        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(JwtService.IMPERSONATION_TOKEN_VALIDITY));
        assertThat(issued.droppedAuthorities())
                .as("the seeded role is within the ceiling")
                .isEmpty();
        assertThat(claims(issued.token()).getExpiration().toInstant()).isEqualTo(NOW.plusSeconds(900));
        assertThat(claims(issued.token()).getIssuedAt().toInstant()).isEqualTo(NOW);

        ArgumentCaptor<JwtToken> stored = ArgumentCaptor.forClass(JwtToken.class);
        verify(jwtTokenRepository).save(stored.capture());
        assertThat(stored.getValue().getToken()).isEqualTo(issued.token());
        assertThat(stored.getValue().getRefreshToken()).isNull();
        assertThat(stored.getValue().getRefreshExpiresAt()).isNull();
        assertThat(stored.getValue().getSubject()).isEqualTo(SUBJECT);
        assertThat(stored.getValue().getIssuedAt()).isEqualTo(NOW);
        assertThat(stored.getValue().getExpiresAt()).isEqualTo(NOW.plusSeconds(900));
    }

    @Test
    @DisplayName("validateToken accepts it once the row is found under its own tid, like any access token")
    void validateTokenAcceptsIt() {
        IssuedImpersonationToken issued = mintUnderTarget();
        JwtToken stored = new JwtToken();
        stored.setToken(issued.token());
        stored.setIssuedAt(NOW);
        stored.setExpiresAt(issued.expiresAt());
        stored.setSubject(SUBJECT);
        when(jwtTokenRepository.findByToken(issued.token())).thenAnswer(inv -> {
            assertThat(TenantContext.require())
                    .as("looked up under the token's tid")
                    .isEqualTo(TARGET_TENANT);
            return Optional.of(stored);
        });

        // Unbound caller: the token's own tid drives the lookup.
        assertThat(sut.validateToken(issued.token())).isTrue();
    }

    @Test
    @DisplayName("refreshAccessToken refuses it with InvalidRefreshTokenException (401 INVALID_REFRESH_TOKEN)")
    void refreshRefusesIt() {
        IssuedImpersonationToken issued = mintUnderTarget();

        assertThatThrownBy(() -> sut.refreshAccessToken(issued.token()))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("Impersonation tokens cannot be refreshed");
        assertThat(sut.validateRefreshToken(issued.token())).isFalse();
        verify(jwtTokenRepository, never()).findByRefreshToken(any());
        verify(jwtTokenRepository, never()).delete(any());
        verify(tokenRevocationManager, never()).revokeToken(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName(
            "a write grant added to SUPPORT never reaches perm_bits: dropped by the read-only ceiling and reported")
    void readOnlyCeilingDropsWriteGrants() {
        when(roleAuthorityService.expandRolesToAuthorities(Set.of("SUPPORT")))
                .thenReturn(Set.of(
                        "ROLE_SUPPORT",
                        "crm:party:view",
                        "security:user:delete",
                        "order:order:create",
                        "people:employee_pii:view",
                        "platform:tenant:read"));

        IssuedImpersonationToken issued = mintUnderTarget();

        assertThat(sut.getAuthoritiesFromToken(issued.token())).containsExactly("crm:party:view");
        assertThat(PermissionBitsetCodec.hasPermission(
                        claims(issued.token()).get(JwtService.PERM_BITS, String.class),
                        PermissionCode.SECURITY__USER__DELETE))
                .isFalse();
        assertThat(issued.droppedAuthorities())
                .containsExactly(
                        "order:order:create",
                        "people:employee_pii:view",
                        "platform:tenant:read",
                        "security:user:delete");
    }

    @Test
    @DisplayName("an expired impersonation token presented for refresh is still the 401, not the generic 400")
    void expiredImpersonationTokenRefreshIs401() {
        String expired = Jwts.builder()
                .id("jti-expired")
                .subject(SUBJECT)
                .issuer("pos-security-service")
                .audience()
                .add("api-gateway")
                .and()
                .claim(JwtService.TID, TARGET_TENANT.toString())
                .claim(JwtService.TOKEN_USE, JwtService.TOKEN_USE_IMPERSONATION)
                .issuedAt(Date.from(NOW.minusSeconds(1800)))
                .expiration(Date.from(NOW.minusSeconds(900)))
                .signWith(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .compact();

        assertThatThrownBy(() -> sut.refreshAccessToken(expired))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("Impersonation tokens cannot be refreshed");
        assertThat(sut.validateRefreshToken(expired)).isFalse();

        // An expired ordinary refresh token keeps the generic answer: only token_use is read early.
        String expiredRefresh = Jwts.builder()
                .id("jti-expired-refresh")
                .subject("alice")
                .issuer("pos-security-service")
                .audience()
                .add("api-gateway")
                .and()
                .claim(JwtService.TID, TARGET_TENANT.toString())
                .claim("type", "refresh")
                .expiration(Date.from(NOW.minusSeconds(1)))
                .signWith(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .compact();
        assertThatThrownBy(() -> sut.refreshAccessToken(expiredRefresh))
                .isInstanceOf(SecurityValidationException.class)
                .hasMessage("Invalid refresh token");
    }

    @Test
    @DisplayName("a blank subject, a blank operator or an empty role set is refused before anything is signed")
    void inputValidation() {
        TenantContext.bind(TARGET_TENANT);
        assertThatThrownBy(() -> sut.generateImpersonationToken(" ", OPERATOR_ID, "admin.platform", Set.of("SUPPORT")))
                .isInstanceOf(SecurityValidationException.class);
        assertThatThrownBy(() -> sut.generateImpersonationToken(SUBJECT, OPERATOR_ID, " ", Set.of("SUPPORT")))
                .isInstanceOf(SecurityValidationException.class);
        assertThatThrownBy(() -> sut.generateImpersonationToken(SUBJECT, OPERATOR_ID, "admin.platform", Set.of()))
                .isInstanceOf(SecurityValidationException.class);
        verify(jwtTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("revokeAllTokensForUser tolerates the missing refresh half")
    void revocationTolerantOfNoRefreshHalf() {
        IssuedImpersonationToken issued = mintUnderTarget();
        JwtToken stored = new JwtToken();
        stored.setToken(issued.token());
        stored.setSubject(SUBJECT);
        when(jwtTokenRepository.findAllBySubject(SUBJECT)).thenReturn(List.of(stored));

        sut.revokeAllTokensForUser(SUBJECT);

        verify(tokenRevocationManager)
                .revokeToken(org.mockito.ArgumentMatchers.eq(issued.jti()), org.mockito.ArgumentMatchers.anyLong());
        verify(jwtTokenRepository).deleteAll(List.of(stored));
    }

    @Test
    @DisplayName("the stored row records the operator, the only revocable link back to the human")
    void storedRowNamesTheOperator() {
        ArgumentCaptor<JwtToken> captor = ArgumentCaptor.forClass(JwtToken.class);
        IssuedImpersonationToken issued = mintUnderTarget();

        verify(jwtTokenRepository).save(captor.capture());
        JwtToken saved = captor.getValue();
        assertThat(saved.getToken()).isEqualTo(issued.token());
        assertThat(saved.getSubject()).isEqualTo(SUBJECT);
        assertThat(saved.getRefreshToken()).isNull();
        // The subject is synthetic and the row is in the target tenant, so neither identifies the
        // operator: without this column nothing could revoke the token when the operator is
        // disabled or loses their platform role (ADR-0062 §7, WS2b-4).
        assertThat(saved.getImpersonatedByUserId()).isEqualTo(OPERATOR_ID);
    }

    @Test
    @DisplayName("revokeImpersonationTokensMintedBy deletes the operator's rows in the bound tenant and kills the jti")
    void revokeByOperatorEndsTheToken() {
        IssuedImpersonationToken issued = mintUnderTarget();
        JwtToken stored = new JwtToken();
        stored.setToken(issued.token());
        stored.setSubject(SUBJECT);
        stored.setImpersonatedByUserId(OPERATOR_ID);
        when(jwtTokenRepository.findAllByImpersonatedByUserId(OPERATOR_ID)).thenReturn(List.of(stored));

        int revoked = TenantContext.callAs(TARGET_TENANT, () -> sut.revokeImpersonationTokensMintedBy(OPERATOR_ID));

        assertThat(revoked).isEqualTo(1);
        verify(tokenRevocationManager)
                .revokeToken(org.mockito.ArgumentMatchers.eq(issued.jti()), org.mockito.ArgumentMatchers.anyLong());
        verify(jwtTokenRepository).deleteAll(List.of(stored));
    }

    @Test
    @DisplayName("an operator with no rows in the bound tenant deletes nothing")
    void revokeByOperatorWithNoRowsIsANoOp() {
        when(jwtTokenRepository.findAllByImpersonatedByUserId(OPERATOR_ID)).thenReturn(List.of());

        assertThat(TenantContext.callAs(TARGET_TENANT, () -> sut.revokeImpersonationTokensMintedBy(OPERATOR_ID)))
                .isZero();

        verify(jwtTokenRepository, never()).deleteAll(any());
        verify(tokenRevocationManager, never()).revokeToken(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    private static TenantResolver tenantResolver() {
        TenancyProperties properties = new TenancyProperties();
        properties.setDefaultTenantId(DEFAULT_TENANT);
        return new TenantResolver(properties);
    }
}
