package com.positivity.securityservice.internal.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Token lifetimes bound from {@code pos.security.jwt.*} (#2135).
 *
 * <p>Both are <em>wall-clock</em> durations. That distinction matters because {@code exp} is minted
 * from the application {@link java.time.Clock}, which under the {@code accelerated} profile is the
 * shared {@code ScaledClock}: adding a lifetime's worth of seconds to it adds <em>virtual</em>
 * seconds, and an hour-long token lasted {@code 3600 / scale} of real time — about one second at
 * scale 2920. {@code JwtServiceImpl} therefore projects a lifetime through the clock
 * ({@code ScaledClock#instantAfter}) rather than adding its seconds, so the configured hour is an
 * hour of real time whatever the clock is doing.
 *
 * <p>Only the natural lifetimes are expressed this way. The ADR-0061 §4 clamps (end of the earliest
 * contributing staffing assignment, earliest end of a contributing role assignment) are clock
 * instants derived from effective-dated rows and are compared in clock time on purpose: a token
 * must never outlive the assignment it was minted from, however fast the clock runs.
 *
 * @param accessTokenTtl how long an access token lives in wall-clock terms; default one hour
 * @param refreshTokenTtl how long a refresh token lives in wall-clock terms; default seven days
 */
@ConfigurationProperties(prefix = "pos.security.jwt")
public record JwtLifetimeProperties(
        @DefaultValue("PT1H") Duration accessTokenTtl,
        @DefaultValue("P7D") Duration refreshTokenTtl) {

    // (d) defensive/internal: these guard `pos.security.jwt.*` property binding at Spring context
    // startup (@ConfigurationProperties), never a value supplied on an HTTP request, so there is
    // no controller path to reach them. Left as IllegalArgumentException — Spring's own
    // property-binding failure reporting expects it, and GlobalExceptionHandler never sees it.
    public JwtLifetimeProperties {
        if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            throw new IllegalArgumentException("pos.security.jwt.access-token-ttl must be positive");
        }
        if (refreshTokenTtl == null || refreshTokenTtl.isNegative() || refreshTokenTtl.isZero()) {
            throw new IllegalArgumentException("pos.security.jwt.refresh-token-ttl must be positive");
        }
    }

    /** The lifetimes every deployment gets without configuring anything: one hour and seven days. */
    public static JwtLifetimeProperties defaults() {
        return new JwtLifetimeProperties(Duration.ofHours(1), Duration.ofDays(7));
    }

    /**
     * Redis TTL, in seconds, for a revoked access token's JTI. Redis expires keys on wall time, so
     * this is the wall-clock lifetime and not a clock-projected instant — an upper bound on what is
     * left of the revoked token's life, which is what the revocation list needs.
     */
    public long accessTokenRevocationSeconds() {
        return atLeastOneSecond(accessTokenTtl);
    }

    /** Redis TTL, in seconds, for a revoked refresh token's JTI. See {@link #accessTokenRevocationSeconds()}. */
    public long refreshTokenRevocationSeconds() {
        return atLeastOneSecond(refreshTokenTtl);
    }

    private static long atLeastOneSecond(Duration ttl) {
        // TokenRevocationManager rejects a non-positive TTL, and a sub-second lifetime truncates
        // to zero seconds.
        return Math.max(1L, ttl.toSeconds());
    }
}
