package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.JwtToken;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.JwtTokenRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.security.service.JwtService;
import io.jsonwebtoken.JwtException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Person-keyed token revocation over the existing {@code TokenRevocationManager} + {@code jwt_token}
 * path (ADR-0061 §4, #1874).
 *
 * <p><b>Redis-unavailable policy: fail-open with loud alerting.</b> {@code
 * TokenRevocationManager.revokeToken} returns {@code false} when Redis is disabled, unreachable,
 * or the write failed after retries. This service then still deletes the {@code jwt_token} rows
 * (so {@code JwtService.validateToken}, which consults the row, refuses the token on this module's
 * bearer path), increments {@value #REDIS_UNAVAILABLE_METRIC} by the number of JTIs that missed
 * Redis, logs at WARN, and returns normally so the driving event is not failed or retried.
 * Fail-closed was rejected: it would refuse every token platform-wide for the duration of a Redis
 * outage, and the #1873 {@code exp} clamp already bounds the stale window to the end of the
 * assignment's effective date, so fail-open costs at most that window. The residual risk is that
 * services validating the signature alone (the gateway) never consult either store — see the
 * module README, "Revocation on assignment change".
 */
@Slf4j
@Service
public class PersonTokenRevocationServiceImpl implements PersonTokenRevocationService {

    static final String REDIS_UNAVAILABLE_METRIC = "security.token-revocation.redis-unavailable";

    private final Clock clock;
    private final UserRepository userRepository;
    private final JwtTokenRepository jwtTokenRepository;
    private final JwtService jwtService;
    private final TokenRevocationManager tokenRevocationManager;
    private final Counter redisUnavailableCounter;

    public PersonTokenRevocationServiceImpl(
            Clock clock,
            UserRepository userRepository,
            JwtTokenRepository jwtTokenRepository,
            JwtService jwtService,
            TokenRevocationManager tokenRevocationManager,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.userRepository = userRepository;
        this.jwtTokenRepository = jwtTokenRepository;
        this.jwtService = jwtService;
        this.tokenRevocationManager = tokenRevocationManager;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.redisUnavailableCounter = registry == null
                ? null
                : Counter.builder(REDIS_UNAVAILABLE_METRIC)
                        .description("Token JTIs whose Redis revocation was skipped or failed because Redis was"
                                + " unavailable; the jwt_token row was still deleted (fail-open, ADR-0061 §4)")
                        .tag("trigger", "staffing-assignment")
                        .register(registry);
    }

    @Override
    @Transactional
    public int revokeLiveTokens(@NonNull UUID personId) {
        Instant now = Instant.now(clock);
        List<User> users = userRepository.findAllByPersonId(personId);
        int revokedRows = 0;
        int redisMisses = 0;
        for (User user : users) {
            // Expired access tokens are skipped: nothing live to revoke, and their refresh token
            // re-enters generateTokenPair, which re-reads the projection.
            List<JwtToken> live = jwtTokenRepository.findAllBySubjectAndExpiresAtAfter(user.getUsername(), now);
            for (JwtToken row : live) {
                if (!revokeJtiInRedis(row.getToken(), row.getExpiresAt(), now, "access")) {
                    redisMisses++;
                }
                if (!revokeJtiInRedis(row.getRefreshToken(), row.getRefreshExpiresAt(), now, "refresh")) {
                    redisMisses++;
                }
            }
            if (!live.isEmpty()) {
                // Same DB marking as refreshAccessToken / revokeAllTokensForUser: the row is the
                // only store validateToken consults, so deleting it is the revocation of record.
                jwtTokenRepository.deleteAll(live);
                revokedRows += live.size();
            }
        }
        if (redisMisses > 0) {
            if (redisUnavailableCounter != null) {
                redisUnavailableCounter.increment(redisMisses);
            }
            log.warn(
                    "Redis unavailable during token revocation (fail-open): jwt_token rows deleted but {} JTI(s)"
                            + " not written to Redis for personId={}; tokens stay valid where only the"
                            + " signature is checked until exp",
                    redisMisses,
                    personId);
        }
        log.info("Revoked live tokens for personId={}: users={} tokens={}", personId, users.size(), revokedRows);
        return revokedRows;
    }

    /**
     * @return {@code false} only when the JTI should have been written to Redis and was not
     *     (unavailable or failed); a token that is unparsable, has no JTI, or is already expired
     *     has nothing to write and returns {@code true}
     */
    private boolean revokeJtiInRedis(String token, Instant expiresAt, Instant now, String tokenType) {
        String jti;
        try {
            jti = jwtService.getJtiFromToken(token);
        } catch (JwtException e) {
            log.debug(
                    "Skipping Redis revocation of {} token that no longer parses: error={}",
                    tokenType,
                    e.getClass().getSimpleName());
            return true;
        }
        if (jti == null) {
            return true;
        }
        long secondsLeft = ChronoUnit.SECONDS.between(now, expiresAt);
        if (secondsLeft <= 0) {
            return true;
        }
        // Redis SET with TTL: writing an already-revoked JTI again is a no-op overwrite.
        return tokenRevocationManager.revokeToken(jti, secondsLeft);
    }
}
