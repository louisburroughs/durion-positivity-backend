package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.domain.PermissionBitsetCodec;
import com.positivity.securityservice.internal.domain.RoleGrant;
import com.positivity.securityservice.internal.dto.UserDto;
import com.positivity.securityservice.internal.entity.JwtToken;
import com.positivity.securityservice.internal.enums.LocationHierarchy;
import com.positivity.securityservice.internal.enums.LocationScope;
import com.positivity.securityservice.internal.enums.PermissionCode;
import com.positivity.securityservice.internal.exception.SecurityValidationException;
import com.positivity.securityservice.internal.repository.JwtTokenRepository;
import com.positivity.securityservice.internal.security.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The location-scope claims (ADR-0061 §2, #1868), the location-reach effective-dating clamp on
 * {@code exp} (ADR-0061 §4, #1873), and the role-assignment effective-dating clamp on {@code exp}
 * (ADR-0061 §4 amendment, 2026-09-09, #1914 phase 3). Same harness as {@link JwtServiceImplTest},
 * except the clock is mutable so a refresh can be issued later than the original token.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtServiceImpl — location scope claims and exp clamp")
class JwtServiceImplLocationScopeTest {

    /** Ten minutes before the end of 2026-09-07 in the issuer's zone. */
    private static final Instant T0 = Instant.parse("2026-09-07T23:50:00Z");

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);
    private static final long ACCESS_TTL_SECONDS = 3600L;
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PERSON_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID NODE_A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID NODE_B = UUID.fromString("00000000-0000-7000-8000-00000000000b");
    private static final UUID REGION = UUID.fromString("00000000-0000-7000-8000-0000000000ee");

    private static final String JE_VIEW = PermissionCode.ACCOUNTING__JE__VIEW.code();
    private static final String JE_CREATE = PermissionCode.ACCOUNTING__JE__CREATE.code();
    private static final String ADJ_APPROVE = PermissionCode.INVENTORY__ADJUSTMENT__APPROVE.code();

    private final MutableClock clock = new MutableClock(T0, ZoneOffset.UTC);

    @Mock
    private JwtTokenRepository jwtTokenRepository;

    @Mock
    private RoleAuthorityService roleAuthorityService;

    @Mock
    private UserService userService;

    @Mock
    private TokenRevocationManager tokenRevocationManager;

    @Mock
    private UserDetailsService userDetailsService;

    @Mock
    private StaffingAssignmentProjectionService projection;

    private JwtServiceImpl sut;

    @BeforeEach
    void setUp() {
        sut = new JwtServiceImpl(
                clock,
                jwtTokenRepository,
                roleAuthorityService,
                userService,
                tokenRevocationManager,
                userDetailsService,
                projection);
        ReflectionTestUtils.setField(sut, "jwtSecret", "this-is-a-long-test-secret-key-with-at-least-32-chars");
        ReflectionTestUtils.invokeMethod(sut, "initializeSecretKey");

        lenient()
                .when(roleAuthorityService.expandRolesToAuthorities(any()))
                .thenReturn(Set.of("ROLE_TECHNICIAN", JE_VIEW, JE_CREATE, ADJ_APPROVE));
        lenient().when(roleAuthorityService.resolveRoleGrants(any())).thenReturn(List.of());
        lenient().when(jwtTokenRepository.save(any(JwtToken.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient()
                .when(userService.getUserById(any(UUID.class)))
                .thenReturn(Optional.of(UserDto.builder()
                        .id(USER_ID)
                        .username("alice")
                        .personId(PERSON_ID)
                        .roles(Set.of("TECHNICIAN"))
                        .build()));
        lenient()
                .when(userDetailsService.loadUserByUsername(anyString()))
                .thenAnswer(inv -> User.withUsername(inv.getArgument(0))
                        .password("{noop}unused")
                        .roles("TECHNICIAN")
                        .build());
    }

    // ---------------------------------------------------------------------------------------
    // Claims (#1868)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a LOCATION role's grants land in the bitset of its hierarchy; perm_bits is unchanged")
    void scopedGrants_landInTheirHierarchyBitset_permBitsUnchanged() {
        grants(
                grant("ACCOUNTANT", LocationScope.LOCATION, LocationHierarchy.FINANCIAL, JE_VIEW),
                grant("INVENTORY_MANAGER", LocationScope.LOCATION, LocationHierarchy.OTHER, ADJ_APPROVE));
        nodes(NODE_A);

        String token = issue(PERSON_ID);

        assertThat(sut.getFinancialLocationScopedPermissionsFromToken(token)).containsExactly(JE_VIEW);
        assertThat(sut.getOtherLocationScopedPermissionsFromToken(token)).containsExactly(ADJ_APPROVE);
        // perm_bits still carries every grant, exactly as before ADR-0061.
        assertThat(sut.getAuthoritiesFromToken(token)).containsExactlyInAnyOrder(JE_VIEW, JE_CREATE, ADJ_APPROVE);
        assertThat(sut.getLocationScopeFromToken(token))
                .contains(new JwtService.LocationScopeClaim(JwtService.LOC_SCOPE_VERSION, List.of(NODE_A)));
    }

    @Test
    @DisplayName("union semantics: a permission any ALL role grants is global and in neither bitset")
    void allRoleGrant_isGlobal_broaderGrantWins() {
        grants(
                grant("INVENTORY_CONTROLLER", LocationScope.ALL, LocationHierarchy.OTHER, ADJ_APPROVE),
                grant("INVENTORY_MANAGER", LocationScope.LOCATION, LocationHierarchy.OTHER, ADJ_APPROVE, JE_VIEW));
        nodes(NODE_A);

        String token = issue(PERSON_ID);

        assertThat(sut.getOtherLocationScopedPermissionsFromToken(token)).containsExactly(JE_VIEW);
        assertThat(sut.getFinancialLocationScopedPermissionsFromToken(token)).isEmpty();
        assertThat(sut.getAuthoritiesFromToken(token)).contains(ADJ_APPROVE);
    }

    @Test
    @DisplayName("a permission granted along both hierarchies is in both bitsets")
    void permissionGrantedBothWays_isInBothBitsets() {
        grants(
                grant("GENERAL_MANAGER", LocationScope.LOCATION, LocationHierarchy.FINANCIAL, JE_VIEW),
                grant("MANAGER", LocationScope.LOCATION, LocationHierarchy.OTHER, JE_VIEW));
        nodes(NODE_A);

        String token = issue(PERSON_ID);

        assertThat(sut.getFinancialLocationScopedPermissionsFromToken(token)).containsExactly(JE_VIEW);
        assertThat(sut.getOtherLocationScopedPermissionsFromToken(token)).containsExactly(JE_VIEW);
    }

    @Test
    @DisplayName(
            "loc_scope is a versioned object {\"v\":1,\"nodes\":[...]} with the assigned node ids, not a bare list")
    void locScope_isVersionedObjectOfAssignedNodes() {
        grants(grant("TECHNICIAN", LocationScope.LOCATION, LocationHierarchy.OTHER, JE_VIEW));
        nodes(NODE_A, NODE_B);

        String token = issue(PERSON_ID);

        String payload = payloadJson(token);
        assertThat(payload)
                .contains("\"loc_scope\":{\"v\":1,\"nodes\":[\"" + NODE_A + "\",\"" + NODE_B + "\"]}")
                .contains("\"loc_fin_bits\":\"\"")
                .contains("\"loc_oth_bits\":\"");
        assertThat(sut.getLocationScopeFromToken(token))
                .contains(new JwtService.LocationScopeClaim(1, List.of(NODE_A, NODE_B)));
        verify(projection).assignedLocationIds(PERSON_ID, TODAY);
    }

    @Test
    @DisplayName("a parent-node assignment is carried verbatim: the issuer never expands a hierarchy")
    void parentAssignment_isNotExpanded() {
        grants(grant("MANAGER", LocationScope.LOCATION, LocationHierarchy.OTHER, JE_VIEW));
        nodes(REGION);

        String token = issue(PERSON_ID);

        assertThat(sut.getLocationScopeFromToken(token).orElseThrow().nodes()).containsExactly(REGION);
        // The projection is the only collaborator consulted about locations: two reads, no
        // hierarchy walk, no location client.
        verify(projection).assignedLocationIds(PERSON_ID, TODAY);
        verify(projection).earliestEffectiveTo(PERSON_ID, TODAY);
        verifyNoMoreInteractions(projection);
    }

    @Test
    @DisplayName(
            "without location-scoped grants both bitsets are empty, loc_scope is absent and the projection is never read")
    void noScopedGrants_emptyBitsets_noLocScope_noProjectionRead() {
        grants(grant("ADMIN", LocationScope.ALL, LocationHierarchy.OTHER, JE_VIEW, JE_CREATE, ADJ_APPROVE));

        String token = issue(PERSON_ID);

        Claims claims = claims(token);
        assertThat(claims.get(JwtService.LOC_FIN_BITS, String.class)).isEmpty();
        assertThat(claims.get(JwtService.LOC_OTH_BITS, String.class)).isEmpty();
        assertThat(claims.containsKey(JwtService.LOC_SCOPE)).isFalse();
        assertThat(sut.getLocationScopeFromToken(token)).isEmpty();
        assertThat(claims.getExpiration().toInstant()).isEqualTo(T0.plusSeconds(ACCESS_TTL_SECONDS));
        verifyNoInteractions(projection);
    }

    @Test
    @DisplayName("fail closed: scoped grants with a null personId emit the bitsets and omit loc_scope")
    void nullPersonId_failsClosed() {
        grants(grant("TECHNICIAN", LocationScope.LOCATION, LocationHierarchy.OTHER, JE_VIEW));

        String token = issue(null);

        assertThat(sut.getOtherLocationScopedPermissionsFromToken(token)).containsExactly(JE_VIEW);
        assertThat(claims(token).containsKey(JwtService.LOC_SCOPE)).isFalse();
        assertThat(sut.getLocationScopeFromToken(token)).isEmpty();
        assertThat(payloadJson(token)).doesNotContain("ALL");
        verifyNoInteractions(projection);
    }

    @Test
    @DisplayName("fail closed: scoped grants with an empty projection emit the bitsets and omit loc_scope, never ALL")
    void emptyProjection_failsClosed() {
        grants(grant("TECHNICIAN", LocationScope.LOCATION, LocationHierarchy.OTHER, JE_VIEW));
        nodes();

        String token = issue(PERSON_ID);

        assertThat(sut.getOtherLocationScopedPermissionsFromToken(token)).containsExactly(JE_VIEW);
        assertThat(claims(token).containsKey(JwtService.LOC_SCOPE)).isFalse();
        assertThat(sut.getLocationScopeFromToken(token)).isEmpty();
        assertThat(payloadJson(token)).doesNotContain("\"loc_scope\"").doesNotContain("ALL");
    }

    @Test
    @DisplayName("refresh tokens carry none of the three scope claims (ADR-0040 §3)")
    void refreshToken_carriesNoScopeClaims() {
        grants(grant("TECHNICIAN", LocationScope.LOCATION, LocationHierarchy.OTHER, JE_VIEW));
        nodes(NODE_A);

        JwtService.TokenPair pair = sut.generateTokenPair("alice", USER_ID, PERSON_ID, Set.of("TECHNICIAN"));

        Claims refresh = claims(pair.refreshToken());
        assertThat(refresh.containsKey(JwtService.LOC_FIN_BITS)).isFalse();
        assertThat(refresh.containsKey(JwtService.LOC_OTH_BITS)).isFalse();
        assertThat(refresh.containsKey(JwtService.LOC_SCOPE)).isFalse();
        assertThat(refresh.containsKey(JwtService.PERM_BITS)).isFalse();
        assertThat(refresh).containsEntry("type", "refresh");
    }

    @Test
    @DisplayName("CATALOG_VERSION is not bumped by the scope claims: pinned at 84, and perm_ver still equals it")
    void catalogVersion_unchanged() {
        grants(grant("TECHNICIAN", LocationScope.LOCATION, LocationHierarchy.OTHER, JE_VIEW));
        nodes(NODE_A);

        String token = issue(PERSON_ID);

        // The literal moves only when a permission is added to the catalog — #1924 added the
        // platform:* families at bits 520-528, taking this from 83 to 84. What this test guards is
        // that the location-scope claims are not what moved it: they ride the same catalog version.
        assertThat(PermissionCode.CATALOG_VERSION).isEqualTo(84);
        Claims claims = claims(token);
        assertThat(claims.get(JwtService.PERM_VER, Integer.class)).isEqualTo(PermissionCode.CATALOG_VERSION);
        // Both scope bitsets decode under that same version: same codec, same bit indexes.
        assertThat(PermissionBitsetCodec.decodeToPermissions(
                        claims.get(JwtService.LOC_OTH_BITS, String.class), PermissionCode.CATALOG_VERSION))
                .containsExactly(PermissionCode.ACCOUNTING__JE__VIEW);
    }

    @Test
    @DisplayName("getLocationScopeFromToken rejects a loc_scope that is not the versioned object")
    void getLocationScopeFromToken_rejectsNonObjectClaim() {
        SecretKey key = (SecretKey) ReflectionTestUtils.getField(sut, "secretKey");
        String forged = Jwts.builder()
                .subject("alice")
                .issuer("pos-security-service")
                .audience()
                .add("api-gateway")
                .and()
                .claim(JwtService.LOC_SCOPE, "ALL")
                .issuedAt(Date.from(T0))
                .expiration(Date.from(T0.plusSeconds(60)))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> sut.getLocationScopeFromToken(forged))
                .isInstanceOf(SecurityValidationException.class)
                .hasMessageContaining("loc_scope");
    }

    // ---------------------------------------------------------------------------------------
    // exp clamp (#1873)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("issued ten minutes before the assignment ends, exp is ten minutes out, not sixty")
    void exp_clampedToEndOfEarliestAssignment() {
        grants(grant("TECHNICIAN", LocationScope.LOCATION, LocationHierarchy.OTHER, JE_VIEW));
        nodes(NODE_A);
        when(projection.earliestEffectiveTo(PERSON_ID, TODAY)).thenReturn(Optional.of(TODAY));

        JwtService.TokenPair pair = sut.generateTokenPair("alice", USER_ID, PERSON_ID, Set.of("TECHNICIAN"));

        Instant endOfToday = Instant.parse("2026-09-08T00:00:00Z");
        assertThat(claims(pair.accessToken()).getExpiration().toInstant())
                .isEqualTo(endOfToday)
                .isEqualTo(T0.plusSeconds(600));
        // The refresh token keeps its own, unclamped lifetime.
        assertThat(claims(pair.refreshToken()).getExpiration().toInstant()).isEqualTo(T0.plusSeconds(604800L));
        // The stored row agrees with the claim.
        verify(jwtTokenRepository)
                .save(org.mockito.ArgumentMatchers.argThat(row -> endOfToday.equals(row.getExpiresAt())));
    }

    @Test
    @DisplayName("an assignment ending after the token's natural lifetime does not shorten it")
    void exp_notClampedWhenAssignmentOutlivesToken() {
        grants(grant("TECHNICIAN", LocationScope.LOCATION, LocationHierarchy.OTHER, JE_VIEW));
        nodes(NODE_A);
        when(projection.earliestEffectiveTo(PERSON_ID, TODAY)).thenReturn(Optional.of(LocalDate.of(2026, 9, 30)));

        String token = issue(PERSON_ID);

        assertThat(claims(token).getExpiration().toInstant()).isEqualTo(T0.plusSeconds(ACCESS_TTL_SECONDS));
    }

    @Test
    @DisplayName("an open-ended assignment does not clamp")
    void exp_notClampedWhenNoAssignmentEnds() {
        grants(grant("TECHNICIAN", LocationScope.LOCATION, LocationHierarchy.OTHER, JE_VIEW));
        nodes(NODE_A);
        when(projection.earliestEffectiveTo(PERSON_ID, TODAY)).thenReturn(Optional.empty());

        String token = issue(PERSON_ID);

        assertThat(claims(token).getExpiration().toInstant()).isEqualTo(T0.plusSeconds(ACCESS_TTL_SECONDS));
    }

    @Test
    @DisplayName("an assignment that has already ended yields no nodes: fail-closed path, no clamp needed")
    void assignmentAlreadyEnded_failsClosedWithoutClamp() {
        grants(grant("TECHNICIAN", LocationScope.LOCATION, LocationHierarchy.OTHER, JE_VIEW));
        // The projection's effective-at predicate excludes ended rows, so both reads are empty.
        nodes();
        lenient().when(projection.earliestEffectiveTo(PERSON_ID, TODAY)).thenReturn(Optional.empty());

        String token = issue(PERSON_ID);

        Claims claims = claims(token);
        assertThat(claims.containsKey(JwtService.LOC_SCOPE)).isFalse();
        assertThat(claims.getExpiration().toInstant()).isEqualTo(T0.plusSeconds(ACCESS_TTL_SECONDS));
    }

    @Test
    @DisplayName("a refreshed token re-evaluates the clamp and does not inherit the old exp")
    void refresh_reEvaluatesClamp() {
        grants(grant("TECHNICIAN", LocationScope.LOCATION, LocationHierarchy.OTHER, JE_VIEW));
        nodes(NODE_A);
        when(projection.earliestEffectiveTo(PERSON_ID, TODAY)).thenReturn(Optional.of(TODAY));

        JwtService.TokenPair original = sut.generateTokenPair("alice", USER_ID, PERSON_ID, Set.of("TECHNICIAN"));
        Instant originalExp = claims(original.accessToken()).getExpiration().toInstant();
        assertThat(originalExp).isEqualTo(T0.plusSeconds(600));

        // Five minutes later the assignment has been extended: the projection no longer reports an end.
        Instant t1 = T0.plusSeconds(300);
        clock.set(t1);
        when(projection.earliestEffectiveTo(PERSON_ID, TODAY)).thenReturn(Optional.empty());

        JwtToken stored = new JwtToken();
        stored.setToken(original.accessToken());
        stored.setRefreshToken(original.refreshToken());
        stored.setIssuedAt(T0);
        stored.setExpiresAt(originalExp);
        stored.setRefreshExpiresAt(T0.plusSeconds(604800L));
        stored.setSubject("alice");
        when(tokenRevocationManager.isRevoked(anyString())).thenReturn(false);
        doReturn(Optional.of(stored))
                .doReturn(Optional.of(stored))
                .when(jwtTokenRepository)
                .findByRefreshToken(original.refreshToken());

        JwtService.TokenPair refreshed = sut.refreshAccessToken(original.refreshToken());

        Instant refreshedExp = claims(refreshed.accessToken()).getExpiration().toInstant();
        assertThat(refreshedExp)
                .isEqualTo(t1.plusSeconds(ACCESS_TTL_SECONDS))
                .isNotEqualTo(originalExp)
                .isNotEqualTo(T0.plusSeconds(ACCESS_TTL_SECONDS));
        assertThat(sut.getLocationScopeFromToken(refreshed.accessToken())
                        .orElseThrow()
                        .nodes())
                .containsExactly(NODE_A);
    }

    @Test
    @DisplayName("a refreshed token picks up a clamp that did not apply when the original was issued")
    void refresh_appliesNewClamp() {
        grants(grant("TECHNICIAN", LocationScope.LOCATION, LocationHierarchy.OTHER, JE_VIEW));
        nodes(NODE_A);
        when(projection.earliestEffectiveTo(PERSON_ID, TODAY)).thenReturn(Optional.empty());

        JwtService.TokenPair original = sut.generateTokenPair("alice", USER_ID, PERSON_ID, Set.of("TECHNICIAN"));
        assertThat(claims(original.accessToken()).getExpiration().toInstant())
                .isEqualTo(T0.plusSeconds(ACCESS_TTL_SECONDS));

        // The assignment is now ended as of today; the refreshed token must not outlive the day.
        when(projection.earliestEffectiveTo(PERSON_ID, TODAY)).thenReturn(Optional.of(TODAY));
        JwtToken stored = new JwtToken();
        stored.setToken(original.accessToken());
        stored.setRefreshToken(original.refreshToken());
        stored.setIssuedAt(T0);
        stored.setExpiresAt(T0.plusSeconds(ACCESS_TTL_SECONDS));
        stored.setRefreshExpiresAt(T0.plusSeconds(604800L));
        stored.setSubject("alice");
        when(tokenRevocationManager.isRevoked(anyString())).thenReturn(false);
        doReturn(Optional.of(stored))
                .doReturn(Optional.of(stored))
                .when(jwtTokenRepository)
                .findByRefreshToken(original.refreshToken());

        JwtService.TokenPair refreshed = sut.refreshAccessToken(original.refreshToken());

        assertThat(claims(refreshed.accessToken()).getExpiration().toInstant())
                .isEqualTo(Instant.parse("2026-09-08T00:00:00Z"));
    }

    // ---------------------------------------------------------------------------------------
    // role-assignment exp clamp (ADR-0061 §4 amendment, 2026-09-09, #1914 phase 3)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a bounded role assignment clamps exp to its end")
    void exp_clampedToGrantsExpireAt() {
        Instant bound = T0.plusSeconds(120);

        JwtService.TokenPair pair = sut.generateTokenPair("alice", USER_ID, null, Set.of("TECHNICIAN"), bound);

        assertThat(claims(pair.accessToken()).getExpiration().toInstant()).isEqualTo(bound);
        // The refresh token keeps its own, unclamped lifetime.
        assertThat(claims(pair.refreshToken()).getExpiration().toInstant()).isEqualTo(T0.plusSeconds(604800L));
    }

    @Test
    @DisplayName("an open-ended role assignment (grantsExpireAt null) leaves exp at the natural 3600s lifetime")
    void exp_notClampedWhenGrantsExpireAtNull() {
        JwtService.TokenPair pair = sut.generateTokenPair("alice", USER_ID, null, Set.of("TECHNICIAN"), null);

        assertThat(claims(pair.accessToken()).getExpiration().toInstant())
                .isEqualTo(T0.plusSeconds(ACCESS_TTL_SECONDS));
    }

    @Test
    @DisplayName("a role assignment ending after the token's natural lifetime does not shorten it")
    void exp_notClampedWhenGrantsExpireAtOutlivesToken() {
        Instant farBound = T0.plusSeconds(ACCESS_TTL_SECONDS * 10);

        JwtService.TokenPair pair = sut.generateTokenPair("alice", USER_ID, null, Set.of("TECHNICIAN"), farBound);

        assertThat(claims(pair.accessToken()).getExpiration().toInstant())
                .isEqualTo(T0.plusSeconds(ACCESS_TTL_SECONDS));
    }

    @Test
    @DisplayName("both clamps present: the earlier of the location-reach bound and grantsExpireAt wins")
    void exp_bothClampsPresent_earlierWins() {
        grants(grant("TECHNICIAN", LocationScope.LOCATION, LocationHierarchy.OTHER, JE_VIEW));
        nodes(NODE_A);
        // Location-reach clamps to end of today (600s out); the assignment bound is closer still.
        when(projection.earliestEffectiveTo(PERSON_ID, TODAY)).thenReturn(Optional.of(TODAY));
        Instant grantsBound = T0.plusSeconds(120);

        JwtService.TokenPair pair =
                sut.generateTokenPair("alice", USER_ID, PERSON_ID, Set.of("TECHNICIAN"), grantsBound);

        assertThat(claims(pair.accessToken()).getExpiration().toInstant())
                .isEqualTo(grantsBound)
                .isBefore(Instant.parse("2026-09-08T00:00:00Z"));
    }

    @Test
    @DisplayName("both clamps present, location-reach earlier: it wins over a later grantsExpireAt")
    void exp_bothClampsPresent_locationReachEarlierWins() {
        grants(grant("TECHNICIAN", LocationScope.LOCATION, LocationHierarchy.OTHER, JE_VIEW));
        nodes(NODE_A);
        when(projection.earliestEffectiveTo(PERSON_ID, TODAY)).thenReturn(Optional.of(TODAY));
        Instant endOfToday = Instant.parse("2026-09-08T00:00:00Z");
        Instant laterGrantsBound = T0.plusSeconds(ACCESS_TTL_SECONDS * 10);

        JwtService.TokenPair pair =
                sut.generateTokenPair("alice", USER_ID, PERSON_ID, Set.of("TECHNICIAN"), laterGrantsBound);

        assertThat(claims(pair.accessToken()).getExpiration().toInstant()).isEqualTo(endOfToday);
    }

    @Test
    @DisplayName("the internal token-pair path (4-arg overload) is unaffected: no grantsExpireAt clamp applies")
    void internalTokenPairPath_fourArgOverload_noClampApplied() {
        JwtService.TokenPair pair = sut.generateTokenPair("svc.reporting", USER_ID, null, Set.of("TECHNICIAN"));

        assertThat(claims(pair.accessToken()).getExpiration().toInstant())
                .isEqualTo(T0.plusSeconds(ACCESS_TTL_SECONDS));
    }

    @Test
    @DisplayName("a refresh resolves grantsExpireAt from UserService and reapplies the clamp")
    void refresh_appliesGrantsExpireAtClamp() {
        JwtService.TokenPair original = sut.generateTokenPair("alice", USER_ID, null, Set.of("TECHNICIAN"), null);
        assertThat(claims(original.accessToken()).getExpiration().toInstant())
                .isEqualTo(T0.plusSeconds(ACCESS_TTL_SECONDS));

        Instant t1 = T0.plusSeconds(60);
        clock.set(t1);
        Instant bound = t1.plusSeconds(90);
        when(userService.getGrantsExpireAt(USER_ID)).thenReturn(Optional.of(bound));

        JwtToken stored = new JwtToken();
        stored.setToken(original.accessToken());
        stored.setRefreshToken(original.refreshToken());
        stored.setIssuedAt(T0);
        stored.setExpiresAt(T0.plusSeconds(ACCESS_TTL_SECONDS));
        stored.setRefreshExpiresAt(T0.plusSeconds(604800L));
        stored.setSubject("alice");
        when(tokenRevocationManager.isRevoked(anyString())).thenReturn(false);
        doReturn(Optional.of(stored))
                .doReturn(Optional.of(stored))
                .when(jwtTokenRepository)
                .findByRefreshToken(original.refreshToken());

        JwtService.TokenPair refreshed = sut.refreshAccessToken(original.refreshToken());

        assertThat(claims(refreshed.accessToken()).getExpiration().toInstant()).isEqualTo(bound);
    }

    // ---------------------------------------------------------------------------------------
    // Size regression (ADR-0061 §2 measured 996 B for one node with both bitsets full)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("an ADMIN-like token with one assigned node and both bitsets fully populated stays under 1 100 bytes")
    void accessToken_adminLikeProfileWithOneNode_under1100Bytes() {
        Set<String> everyCode =
                Arrays.stream(PermissionCode.values()).map(PermissionCode::code).collect(Collectors.toSet());
        when(roleAuthorityService.expandRolesToAuthorities(any())).thenReturn(everyCode);
        grants(
                new RoleGrant("FIN_ROLE", LocationScope.LOCATION, LocationHierarchy.FINANCIAL, everyCode),
                new RoleGrant("OTH_ROLE", LocationScope.LOCATION, LocationHierarchy.OTHER, everyCode));
        nodes(NODE_A);

        JwtService.TokenPair pair = sut.generateTokenPair("admin.alpha", USER_ID, PERSON_ID, Set.of("ADMIN"));

        int size = pair.accessToken().getBytes(StandardCharsets.UTF_8).length;
        assertThat(sut.getFinancialLocationScopedPermissionsFromToken(pair.accessToken()))
                .hasSize(PermissionCode.values().length);
        assertThat(sut.getOtherLocationScopedPermissionsFromToken(pair.accessToken()))
                .hasSize(PermissionCode.values().length);
        assertThat(size)
                .as("compact-serialised access token is %d bytes (ADR-0061 measured 996 B; limit 1 100 B)", size)
                .isLessThan(1_100);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private static RoleGrant grant(
            String role, LocationScope scope, LocationHierarchy hierarchy, String... permissions) {
        return new RoleGrant(role, scope, hierarchy, Set.of(permissions));
    }

    private void grants(RoleGrant... grants) {
        when(roleAuthorityService.resolveRoleGrants(any())).thenReturn(List.of(grants));
    }

    private void nodes(UUID... nodes) {
        lenient().when(projection.assignedLocationIds(PERSON_ID, TODAY)).thenReturn(List.of(nodes));
    }

    private String issue(UUID personId) {
        return sut.generateTokenPair("alice", USER_ID, personId, Set.of("TECHNICIAN"))
                .accessToken();
    }

    private Claims claims(String token) {
        SecretKey key = (SecretKey) ReflectionTestUtils.getField(sut, "secretKey");
        return Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static String payloadJson(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    /** A settable fixed clock: every read in a test sees the same instant until the test moves it. */
    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void set(Instant newInstant) {
            this.instant = newInstant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutableClock(instant, newZone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
