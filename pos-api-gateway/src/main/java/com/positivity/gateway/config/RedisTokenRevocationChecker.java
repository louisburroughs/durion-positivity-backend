package com.positivity.gateway.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

/**
 * Reads the revocation key space pos-security-service writes (#1883).
 *
 * <p>The contract is one key per revoked token, {@code jwt:revoked:{jti}}, written by
 * {@code TokenRevocationManager} with a TTL matching the token's own expiry. Only presence
 * carries meaning, so this issues {@code EXISTS} and never deserializes a value — which is also
 * why the two processes need only agree on the <em>key</em> encoding (plain UTF-8; see
 * {@code RedisConfig.jwtRevocationRedisTemplate}).
 *
 * <p><b>Fail open, loudly.</b> A lookup that errors or exceeds {@code timeout} resolves to "not
 * revoked" rather than rejecting the request, matching the policy #1874 recorded for the write
 * side. Every degraded lookup increments {@code auth.token.revocation.degraded}; entering and
 * leaving the degraded state each log a WARN once, so a Redis outage is one pair of log lines and
 * a rising counter rather than a line per request.
 *
 * <p>Nothing is cached. A negative cache would be the obvious latency optimisation, but its TTL
 * is exactly the window in which a revoked token still passes — the behaviour this issue exists
 * to remove. The cost is one Redis round trip per authenticated request, tracked by
 * {@code auth.token.revocation.check.duration}.
 */
public class RedisTokenRevocationChecker implements TokenRevocationChecker {

    static final String REVOCATION_KEY_PREFIX = "jwt:revoked:";

    private static final String METRIC_CHECK_DURATION = "auth.token.revocation.check.duration";
    private static final String METRIC_DEGRADED = "auth.token.revocation.degraded";
    private static final String OUTCOME_TAG = "outcome";
    private static final String OUTCOME_REVOKED = "revoked";
    private static final String OUTCOME_CLEAR = "clear";
    private static final String OUTCOME_DEGRADED = "degraded";
    private static final String REASON_TAG = "reason";
    private static final String REASON_TIMEOUT = "timeout";
    private static final String REASON_ERROR = "error";
    private static final Logger LOG = LoggerFactory.getLogger(RedisTokenRevocationChecker.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final Duration timeout;
    private final AtomicBoolean degraded = new AtomicBoolean(false);

    public RedisTokenRevocationChecker(
            @NonNull ReactiveStringRedisTemplate redisTemplate,
            @NonNull MeterRegistry meterRegistry,
            @NonNull Duration timeout) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.timeout = timeout;
    }

    @Override
    public Mono<Boolean> isRevoked(String jti) {
        long startedNanos = System.nanoTime();
        return redisTemplate
                .hasKey(REVOCATION_KEY_PREFIX + jti)
                .timeout(timeout)
                .map(revoked -> {
                    boolean isRevoked = Boolean.TRUE.equals(revoked);
                    clearDegraded();
                    record(startedNanos, isRevoked ? OUTCOME_REVOKED : OUTCOME_CLEAR);
                    return isRevoked;
                })
                .onErrorResume(error -> {
                    boolean timedOut = error instanceof java.util.concurrent.TimeoutException;
                    meterRegistry
                            .counter(METRIC_DEGRADED, REASON_TAG, timedOut ? REASON_TIMEOUT : REASON_ERROR)
                            .increment();
                    record(startedNanos, OUTCOME_DEGRADED);
                    if (degraded.compareAndSet(false, true)) {
                        LOG.warn(
                                "Gateway token-revocation check degraded — accepting tokens without a revocation"
                                        + " lookup until Redis recovers (fail-open, #1883/#1874); reason={} detail={}",
                                timedOut ? REASON_TIMEOUT : REASON_ERROR,
                                error.getMessage());
                    }
                    return Mono.just(Boolean.FALSE);
                });
    }

    private void clearDegraded() {
        if (degraded.compareAndSet(true, false)) {
            LOG.warn("Gateway token-revocation check recovered — revocations are being honoured again");
        }
    }

    private void record(long startedNanos, String outcome) {
        Timer.builder(METRIC_CHECK_DURATION)
                .tag(OUTCOME_TAG, outcome)
                .register(meterRegistry)
                .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }
}
