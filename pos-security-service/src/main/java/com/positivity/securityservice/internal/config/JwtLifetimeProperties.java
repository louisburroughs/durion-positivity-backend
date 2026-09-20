package com.positivity.securityservice.internal.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Token lifetimes bound from {@code pos.security.jwt.*} (#2135).
 *
 * <p>{@code exp} is minted from the application {@link java.time.Clock}, so a lifetime is a number
 * of <em>clock</em> seconds. Under the {@code accelerated} profile that clock is the shared
 * {@code ScaledClock}, and a token's wall-clock life is {@code ttl / scale}: at scale 2920 an
 * hour-long access token expired about one real second after the login that minted it. {@code
 * clockScale} is the multiplier that keeps the configured durations meaningful in wall-clock terms:
 * the minted lifetime is {@code ttl × clockScale} clock seconds, which is {@code ttl} of wall time
 * while the clock is accelerating. The {@code accelerated} profile sets it from {@code
 * pos.time.accelerated.scale}; everywhere else it is 1 and the lifetimes are plain wall time.
 *
 * <p>Only the natural lifetimes scale. The ADR-0061 §4 clamps (end of the earliest contributing
 * staffing assignment, earliest end of a contributing role assignment) are clock instants derived
 * from effective-dated rows and are compared in clock time on purpose: a token must never outlive
 * the assignment it was minted from, however fast the clock runs.
 *
 * @param accessTokenTtl access-token lifetime before scaling; default one hour
 * @param refreshTokenTtl refresh-token lifetime before scaling; default seven days
 * @param clockScale how many clock seconds each wall-clock second of a lifetime is worth; 1 on a
 *     wall clock, the accelerated clock's scale under the {@code accelerated} profile
 */
@ConfigurationProperties(prefix = "pos.security.jwt")
public record JwtLifetimeProperties(
        @DefaultValue("PT1H") Duration accessTokenTtl,
        @DefaultValue("P7D") Duration refreshTokenTtl,
        @DefaultValue("1") double clockScale) {

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
        if (clockScale <= 0.0 || Double.isNaN(clockScale) || Double.isInfinite(clockScale)) {
            throw new IllegalArgumentException("pos.security.jwt.clock-scale must be a finite positive value");
        }
    }

    /** The lifetimes every deployment gets without configuring anything: one hour and seven days. */
    public static JwtLifetimeProperties defaults() {
        return new JwtLifetimeProperties(Duration.ofHours(1), Duration.ofDays(7), 1.0);
    }

    /** Access-token lifetime in clock seconds: {@code accessTokenTtl × clockScale}, at least one. */
    public long accessTokenSeconds() {
        return scaled(accessTokenTtl);
    }

    /** Refresh-token lifetime in clock seconds: {@code refreshTokenTtl × clockScale}, at least one. */
    public long refreshTokenSeconds() {
        return scaled(refreshTokenTtl);
    }

    private long scaled(Duration ttl) {
        // Floored at one second so a fractional scale can never round a lifetime down to zero, which
        // TokenRevocationManager rejects and which would mint an already-expired token.
        return Math.max(1L, Math.round(ttl.getSeconds() * clockScale));
    }
}
