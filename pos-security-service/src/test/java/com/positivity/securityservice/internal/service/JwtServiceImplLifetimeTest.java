package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.config.JwtLifetimeProperties;
import com.positivity.securityservice.internal.domain.RoleGrant;
import com.positivity.securityservice.internal.dto.UserDto;
import com.positivity.securityservice.internal.entity.JwtToken;
import com.positivity.securityservice.internal.enums.LocationHierarchy;
import com.positivity.securityservice.internal.enums.LocationScope;
import com.positivity.securityservice.internal.enums.PermissionCode;
import com.positivity.securityservice.internal.repository.JwtTokenRepository;
import com.positivity.securityservice.internal.security.service.JwtService;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantResolver;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Issue #2135: {@code exp} is minted from the injected {@link Clock}, which under the {@code
 * accelerated} profile is the shared {@code ScaledClock}. A lifetime is therefore clock seconds,
 * and an unscaled hour lasted {@code 3600 / scale} real seconds — about one second at scale 2920,
 * which is what made an accelerated run fail in setup with a 401 on every persona.
 *
 * <p>The clock here is fixed rather than scaled on purpose: {@code JwtServiceImpl} only ever reads
 * {@code Instant.now(clock)}, so what the accelerated deployment changes is the size of the
 * lifetime it adds, and that is what these tests pin.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtServiceImpl — configurable, clock-scaled token lifetimes (#2135)")
class JwtServiceImplLifetimeTest {

    private static final Instant T0 = Instant.parse("2025-09-30T00:30:50Z");
    private static final LocalDate TODAY = LocalDate.of(2025, 9, 30);
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PERSON_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID NODE_A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final String JE_VIEW = PermissionCode.ACCOUNTING__JE__VIEW.code();

    /** The scale the failing accelerated run used: 2920 virtual seconds per real second. */
    private static final double ACCELERATED_SCALE = 2920.0;

    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);

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

    @Test
    @DisplayName("on a wall clock the minted lifetimes are the configured hour and seven days")
    void wallClock_mintsTheConfiguredLifetimes() {
        JwtServiceImpl sut = serviceWith(JwtLifetimeProperties.defaults());

        JwtService.TokenPair pair = sut.generateTokenPair("alice", USER_ID, null, Set.of("TECHNICIAN"));

        assertThat(claims(sut, pair.accessToken()).getExpiration().toInstant()).isEqualTo(T0.plusSeconds(3600L));
        assertThat(claims(sut, pair.refreshToken()).getExpiration().toInstant()).isEqualTo(T0.plusSeconds(604800L));
    }

    @Test
    @DisplayName("configured lifetimes are honoured: a fifteen-minute access token expires in fifteen minutes")
    void configuredLifetimesAreHonoured() {
        JwtServiceImpl sut = serviceWith(new JwtLifetimeProperties(Duration.ofMinutes(15), Duration.ofHours(12), 1.0));

        JwtService.TokenPair pair = sut.generateTokenPair("alice", USER_ID, null, Set.of("TECHNICIAN"));

        assertThat(claims(sut, pair.accessToken()).getExpiration().toInstant()).isEqualTo(T0.plusSeconds(900L));
        assertThat(claims(sut, pair.refreshToken()).getExpiration().toInstant()).isEqualTo(T0.plusSeconds(43200L));
    }

    @Test
    @DisplayName("under the accelerated clock scale an hour-long token lives an hour of WALL time, not 1.2 seconds")
    void acceleratedScale_keepsTheLifetimeInWallTime() {
        JwtServiceImpl sut =
                serviceWith(new JwtLifetimeProperties(Duration.ofHours(1), Duration.ofDays(7), ACCELERATED_SCALE));

        JwtService.TokenPair pair = sut.generateTokenPair("alice", USER_ID, null, Set.of("TECHNICIAN"));

        Instant accessExp = claims(sut, pair.accessToken()).getExpiration().toInstant();
        assertThat(accessExp).isEqualTo(T0.plusSeconds((long) (3600L * ACCELERATED_SCALE)));
        // The property the issue is about: virtual lifetime / scale is the token's real life.
        assertThat(Duration.between(T0, accessExp).dividedBy((long) ACCELERATED_SCALE))
                .as("wall-clock life of the access token")
                .isEqualTo(Duration.ofHours(1));
        assertThat(Duration.between(
                                T0,
                                claims(sut, pair.refreshToken()).getExpiration().toInstant())
                        .dividedBy((long) ACCELERATED_SCALE))
                .as("wall-clock life of the refresh token")
                .isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("the revocation TTLs on refresh follow the configured lifetimes, so Redis keys outlive the tokens")
    void refresh_revokesWithTheConfiguredLifetimes() {
        JwtLifetimeProperties lifetimes =
                new JwtLifetimeProperties(Duration.ofHours(1), Duration.ofDays(7), ACCELERATED_SCALE);
        JwtServiceImpl sut = serviceWith(lifetimes);
        JwtService.TokenPair original = sut.generateTokenPair("alice", USER_ID, null, Set.of("TECHNICIAN"));

        JwtToken stored = new JwtToken();
        stored.setToken(original.accessToken());
        stored.setRefreshToken(original.refreshToken());
        stored.setIssuedAt(T0);
        stored.setExpiresAt(T0.plusSeconds(lifetimes.accessTokenSeconds()));
        stored.setRefreshExpiresAt(T0.plusSeconds(lifetimes.refreshTokenSeconds()));
        stored.setSubject("alice");
        when(jwtTokenRepository.findByRefreshToken(original.refreshToken())).thenReturn(Optional.of(stored));
        when(tokenRevocationManager.isRevoked(anyString())).thenReturn(false);
        lenient()
                .when(tokenRevocationManager.revokeToken(anyString(), anyLong()))
                .thenReturn(true);

        sut.refreshAccessToken(original.refreshToken());

        verify(tokenRevocationManager).revokeToken(anyString(), eq(lifetimes.accessTokenSeconds()));
        verify(tokenRevocationManager).revokeToken(anyString(), eq(lifetimes.refreshTokenSeconds()));
    }

    @Test
    @DisplayName("the ADR-0061 §4 clamps are NOT scaled: a staffing assignment ending today still ends the token")
    void clamps_areNotScaled() {
        JwtServiceImpl sut =
                serviceWith(new JwtLifetimeProperties(Duration.ofHours(1), Duration.ofDays(7), ACCELERATED_SCALE));
        when(roleAuthorityService.resolveRoleGrants(any()))
                .thenReturn(List.of(
                        new RoleGrant("TECHNICIAN", LocationScope.LOCATION, LocationHierarchy.OTHER, Set.of(JE_VIEW))));
        when(projection.assignedLocationIds(PERSON_ID, TODAY)).thenReturn(List.of(NODE_A));
        when(projection.earliestEffectiveTo(PERSON_ID, TODAY)).thenReturn(Optional.of(TODAY));

        JwtService.TokenPair pair = sut.generateTokenPair("alice", USER_ID, PERSON_ID, Set.of("TECHNICIAN"));

        // End of 2025-09-30 UTC — the assignment's bound, unscaled and far short of the scaled
        // natural lifetime. A token must not outlive its assignment however fast the clock runs.
        assertThat(claims(sut, pair.accessToken()).getExpiration().toInstant())
                .isEqualTo(Instant.parse("2025-10-01T00:00:00Z"));
    }

    @Test
    @DisplayName("a bounded role assignment clamps exp even when the natural lifetime is scaled")
    void grantsExpireAtClamp_isNotScaled() {
        JwtServiceImpl sut =
                serviceWith(new JwtLifetimeProperties(Duration.ofHours(1), Duration.ofDays(7), ACCELERATED_SCALE));
        Instant bound = T0.plusSeconds(600);

        JwtService.TokenPair pair = sut.generateTokenPair("alice", USER_ID, null, Set.of("TECHNICIAN"), bound);

        assertThat(claims(sut, pair.accessToken()).getExpiration().toInstant()).isEqualTo(bound);
        // The refresh token keeps its own, scaled lifetime — the clamp is an access-token bound.
        assertThat(claims(sut, pair.refreshToken()).getExpiration().toInstant())
                .isEqualTo(T0.plusSeconds((long) (604800L * ACCELERATED_SCALE)));
    }

    // -------------------------------------------------------------------------------------------

    private JwtServiceImpl serviceWith(JwtLifetimeProperties lifetimes) {
        JwtServiceImpl sut = new JwtServiceImpl(
                clock,
                jwtTokenRepository,
                roleAuthorityService,
                userService,
                tokenRevocationManager,
                userDetailsService,
                projection,
                tenantResolver(),
                lifetimes);
        ReflectionTestUtils.setField(sut, "jwtSecret", "this-is-a-long-test-secret-key-with-at-least-32-chars");
        ReflectionTestUtils.invokeMethod(sut, "initializeSecretKey");

        lenient()
                .when(roleAuthorityService.expandRolesToAuthorities(any()))
                .thenReturn(Set.of("ROLE_TECHNICIAN", JE_VIEW));
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
        return sut;
    }

    private Claims claims(JwtServiceImpl sut, String token) {
        SecretKey key = (SecretKey) ReflectionTestUtils.getField(sut, "secretKey");
        return Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static TenantResolver tenantResolver() {
        TenancyProperties properties = new TenancyProperties();
        properties.setDefaultTenantId(UUID.fromString("01900000-0000-7000-8000-000000000001"));
        return new TenantResolver(properties);
    }
}
