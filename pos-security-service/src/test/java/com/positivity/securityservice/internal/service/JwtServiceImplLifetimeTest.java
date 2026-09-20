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
import com.positivity.time.ScaledClock;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * accelerated} profile is the shared converging {@link ScaledClock}. Adding a lifetime's worth of
 * seconds to that clock adds <em>virtual</em> seconds, so an hour-long access token lasted about
 * one real second at scale 2,920 and every persona's next request came back 401.
 *
 * <p>The fix projects the configured wall-clock lifetime through the clock, which is why these
 * tests drive a real {@code ScaledClock} over a movable base clock and assert on <em>real</em>
 * elapsed time. The three regimes are pinned separately, because a scale multiplier — the obvious
 * fix — is only correct in the first: still accelerating, converged, and a lifetime that spans
 * convergence.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtServiceImpl — wall-clock token lifetimes on an accelerated clock (#2135)")
class JwtServiceImplLifetimeTest {

    /** The scale the failing accelerated run used: 2,920 virtual seconds per real second. */
    private static final double SCALE = 2920.0;

    private static final Instant REAL_START = Instant.parse("2026-09-20T12:00:00Z");

    /**
     * Anchors chosen so convergence is exact and near: the virtual clock trails wall time by
     * 2,919 seconds and runs 2,920×, so the gap closes in {@code 2919 / (2920 - 1) = 1} real
     * second.
     */
    private static final Instant VIRTUAL_START = REAL_START.minusSeconds(2919);

    private static final Duration ACCESS_TTL = Duration.ofHours(1);
    private static final Duration REFRESH_TTL = Duration.ofDays(7);

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PERSON_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID NODE_A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final String JE_VIEW = PermissionCode.ACCOUNTING__JE__VIEW.code();

    private final MutableClock baseClock = new MutableClock(REAL_START, ZoneOffset.UTC);

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

    // -------------------------------------------------------------------------------------------
    // An ordinary wall clock: unchanged behaviour
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("on a wall clock the minted lifetimes are the configured hour and seven days")
    void wallClock_mintsTheConfiguredLifetimes() {
        Clock clock = Clock.fixed(REAL_START, ZoneOffset.UTC);
        JwtServiceImpl sut = serviceOn(clock, JwtLifetimeProperties.defaults());

        JwtService.TokenPair pair = sut.generateTokenPair("alice", USER_ID, null, Set.of("TECHNICIAN"));

        assertThat(expiryOf(sut, clock, pair.accessToken())).isEqualTo(REAL_START.plus(ACCESS_TTL));
        assertThat(expiryOf(sut, clock, pair.refreshToken())).isEqualTo(REAL_START.plus(REFRESH_TTL));
    }

    @Test
    @DisplayName("configured lifetimes are honoured: a fifteen-minute access token expires in fifteen minutes")
    void wallClock_configuredLifetimesAreHonoured() {
        Clock clock = Clock.fixed(REAL_START, ZoneOffset.UTC);
        JwtServiceImpl sut = serviceOn(clock, new JwtLifetimeProperties(Duration.ofMinutes(15), Duration.ofHours(12)));

        JwtService.TokenPair pair = sut.generateTokenPair("alice", USER_ID, null, Set.of("TECHNICIAN"));

        assertThat(expiryOf(sut, clock, pair.accessToken())).isEqualTo(REAL_START.plusSeconds(900));
        assertThat(expiryOf(sut, clock, pair.refreshToken())).isEqualTo(REAL_START.plusSeconds(43200));
    }

    // -------------------------------------------------------------------------------------------
    // The accelerated clock, in all three regimes
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("while accelerating, an hour-long token lives an hour of REAL time, not 1.2 seconds")
    void accelerating_tokenLivesItsWallClockLifetime() {
        // A gap far too wide to close inside the token's life, so the whole hour is spent
        // accelerating: this is the regime the issue was reported in.
        ScaledClock clock = acceleratedClock(REAL_START.minus(Duration.ofDays(365)));
        JwtServiceImpl sut = serviceOn(clock, JwtLifetimeProperties.defaults());

        JwtService.TokenPair pair = sut.generateTokenPair("alice", USER_ID, null, Set.of("TECHNICIAN"));
        Instant accessExp = expiryOf(sut, clock, pair.accessToken());
        Instant refreshExp = expiryOf(sut, clock, pair.refreshToken());

        // Before the fix this was `now + 3600` virtual seconds, i.e. expired 1.2 real seconds later.
        assertThat(tokenIsAliveAfter(clock, accessExp, ACCESS_TTL.minusMinutes(1)))
                .as("still valid 59 real minutes in")
                .isTrue();
        assertThat(tokenIsAliveAfter(clock, accessExp, ACCESS_TTL.plusMinutes(1)))
                .as("expired 61 real minutes in")
                .isFalse();
        assertThat(tokenIsAliveAfter(clock, refreshExp, REFRESH_TTL.minusHours(1)))
                .as("refresh token still valid just under seven real days in")
                .isTrue();
        assertThat(tokenIsAliveAfter(clock, refreshExp, REFRESH_TTL.plusHours(1)))
                .as("refresh token expired just over seven real days in")
                .isFalse();
    }

    @Test
    @DisplayName("after the clock converges the scale is gone: an hour-long token is an hour, not ~1,000 hours")
    void afterConvergence_tokenDoesNotOutliveItsLifetime() {
        ScaledClock clock = acceleratedClock(VIRTUAL_START);
        baseClock.setInstant(REAL_START.plusSeconds(5));
        assertThat(clock.isConverged())
                .as("the 2,919s gap closes in one real second")
                .isTrue();

        JwtServiceImpl sut = serviceOn(clock, JwtLifetimeProperties.defaults());
        JwtService.TokenPair pair = sut.generateTokenPair("alice", USER_ID, null, Set.of("TECHNICIAN"));

        // A converged clock ticks at 1x, so a scale multiplier would mint a 2,920-hour token here.
        assertThat(expiryOf(sut, clock, pair.accessToken()))
                .isEqualTo(REAL_START.plusSeconds(5).plus(ACCESS_TTL));
        assertThat(tokenIsAliveAfter(clock, expiryOf(sut, clock, pair.accessToken()), ACCESS_TTL.plusMinutes(1)))
                .as("expired 61 real minutes in")
                .isFalse();
    }

    @Test
    @DisplayName("a lifetime that spans convergence still ends after its wall-clock length")
    void spanningConvergence_tokenDoesNotOutliveItsLifetime() {
        // Minted while still accelerating, one real second before the clock converges — so all but
        // the first second of the token's hour is spent on a 1x clock.
        ScaledClock clock = acceleratedClock(VIRTUAL_START);
        assertThat(clock.isConverged()).isFalse();

        JwtServiceImpl sut = serviceOn(clock, JwtLifetimeProperties.defaults());
        JwtService.TokenPair pair = sut.generateTokenPair("alice", USER_ID, null, Set.of("TECHNICIAN"));
        Instant accessExp = expiryOf(sut, clock, pair.accessToken());

        assertThat(accessExp).isEqualTo(REAL_START.plus(ACCESS_TTL));
        assertThat(tokenIsAliveAfter(clock, accessExp, ACCESS_TTL.minusMinutes(1)))
                .as("still valid 59 real minutes in")
                .isTrue();
        assertThat(tokenIsAliveAfter(clock, accessExp, ACCESS_TTL.plusMinutes(1)))
                .as("expired 61 real minutes in")
                .isFalse();
    }

    @Test
    @DisplayName("asking for an expiry does not tip the clock into convergence")
    void mintingDoesNotLatchConvergence() {
        ScaledClock clock = acceleratedClock(VIRTUAL_START);
        JwtServiceImpl sut = serviceOn(clock, JwtLifetimeProperties.defaults());

        sut.generateTokenPair("alice", USER_ID, null, Set.of("TECHNICIAN"));

        assertThat(clock.isConverged()).isFalse();
        assertThat(clock.instant()).isEqualTo(VIRTUAL_START);
    }

    // -------------------------------------------------------------------------------------------
    // Revocation TTLs and the ADR-0061 §4 clamps
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the Redis revocation TTLs are wall seconds, because Redis expires keys on wall time")
    void refresh_revokesWithWallClockTtls() {
        ScaledClock clock = acceleratedClock(REAL_START.minus(Duration.ofDays(365)));
        JwtLifetimeProperties lifetimes = JwtLifetimeProperties.defaults();
        JwtServiceImpl sut = serviceOn(clock, lifetimes);
        JwtService.TokenPair original = sut.generateTokenPair("alice", USER_ID, null, Set.of("TECHNICIAN"));

        JwtToken stored = new JwtToken();
        stored.setToken(original.accessToken());
        stored.setRefreshToken(original.refreshToken());
        stored.setIssuedAt(clock.instant());
        stored.setExpiresAt(expiryOf(sut, clock, original.accessToken()));
        stored.setRefreshExpiresAt(expiryOf(sut, clock, original.refreshToken()));
        stored.setSubject("alice");
        when(jwtTokenRepository.findByRefreshToken(original.refreshToken())).thenReturn(Optional.of(stored));
        when(tokenRevocationManager.isRevoked(anyString())).thenReturn(false);
        lenient()
                .when(tokenRevocationManager.revokeToken(anyString(), anyLong()))
                .thenReturn(true);

        sut.refreshAccessToken(original.refreshToken());

        verify(tokenRevocationManager).revokeToken(anyString(), eq(3600L));
        verify(tokenRevocationManager).revokeToken(anyString(), eq(604800L));
    }

    @Test
    @DisplayName("the ADR-0061 §4 clamps stay in clock time: a staffing assignment ending today still ends the token")
    void locationReachClamp_staysInClockTime() {
        ScaledClock clock = acceleratedClock(REAL_START.minus(Duration.ofDays(365)));
        JwtServiceImpl sut = serviceOn(clock, JwtLifetimeProperties.defaults());
        LocalDate virtualToday = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        when(roleAuthorityService.resolveRoleGrants(any()))
                .thenReturn(List.of(
                        new RoleGrant("TECHNICIAN", LocationScope.LOCATION, LocationHierarchy.OTHER, Set.of(JE_VIEW))));
        when(projection.assignedLocationIds(PERSON_ID, virtualToday)).thenReturn(List.of(NODE_A));
        when(projection.earliestEffectiveTo(PERSON_ID, virtualToday)).thenReturn(Optional.of(virtualToday));

        JwtService.TokenPair pair = sut.generateTokenPair("alice", USER_ID, PERSON_ID, Set.of("TECHNICIAN"));

        // End of the assignment's last virtual day, not the projected wall-clock hour: a token must
        // not outlive the assignment it was minted from, however fast the clock runs.
        assertThat(expiryOf(sut, clock, pair.accessToken()))
                .isEqualTo(virtualToday.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    @Test
    @DisplayName("a bounded role assignment clamps exp on the accelerated clock too")
    void grantsExpireAtClamp_staysInClockTime() {
        ScaledClock clock = acceleratedClock(REAL_START.minus(Duration.ofDays(365)));
        JwtServiceImpl sut = serviceOn(clock, JwtLifetimeProperties.defaults());
        Instant bound = clock.instant().plusSeconds(600);

        JwtService.TokenPair pair = sut.generateTokenPair("alice", USER_ID, null, Set.of("TECHNICIAN"), bound);

        assertThat(expiryOf(sut, clock, pair.accessToken())).isEqualTo(bound);
        // The refresh token keeps its own lifetime — the clamp is an access-token bound.
        assertThat(expiryOf(sut, clock, pair.refreshToken())).isAfter(bound);
    }

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    /**
     * Rewinds the base clock to the anchor and hands back a converging clock over it, so a test can
     * move real time forward from a known point.
     */
    private ScaledClock acceleratedClock(Instant virtualStart) {
        baseClock.setInstant(REAL_START);
        return new ScaledClock(baseClock, ZoneOffset.UTC, REAL_START, virtualStart, SCALE, true);
    }

    /** Whether a token with this expiry is still unexpired once {@code realElapsed} of wall time has passed. */
    private boolean tokenIsAliveAfter(ScaledClock clock, Instant expiry, Duration realElapsed) {
        Instant restore = baseClock.instant();
        try {
            baseClock.setInstant(REAL_START.plus(realElapsed));
            return clock.instant().isBefore(expiry);
        } finally {
            baseClock.setInstant(restore);
        }
    }

    private JwtServiceImpl serviceOn(Clock clock, JwtLifetimeProperties lifetimes) {
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

    private Instant expiryOf(JwtServiceImpl sut, Clock clock, String token) {
        SecretKey key = (SecretKey) ReflectionTestUtils.getField(sut, "secretKey");
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getExpiration().toInstant();
    }

    private static TenantResolver tenantResolver() {
        TenancyProperties properties = new TenancyProperties();
        properties.setDefaultTenantId(UUID.fromString("01900000-0000-7000-8000-000000000001"));
        return new TenantResolver(properties);
    }

    /** A base clock the test moves by hand, so "real time passing" is deterministic. */
    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
