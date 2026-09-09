package com.positivity.gateway.config;

import reactor.core.publisher.Mono;

/**
 * Answers, for one access-token {@code jti}, whether the token has been revoked (#1883).
 *
 * <p>The gateway is the security boundary every downstream service trusts, so a revocation that
 * only pos-security-service can see is not a revocation at all: before this check existed a token
 * revoked by logout, {@code revokeAllTokensForUser}, refresh rotation or an ADR-0061 §4
 * reach-narrowing kept passing the gateway until its {@code exp}.
 *
 * <p>Implementations must never signal "unknown". A backing store that is unreachable, slow or
 * disabled resolves to {@code false} — <b>fail open, loudly</b> — matching the policy #1874
 * recorded for the write side in {@code pos-security-service/README.md}: failing closed would
 * refuse every request platform-wide for the length of a Redis outage, while the ADR-0061 §4
 * {@code exp} clamp already bounds the stale window. The degraded state must instead be visible
 * through a metric and a log line.
 */
@FunctionalInterface
public interface TokenRevocationChecker {

    /**
     * A checker that never reports a revocation. Used when the check is switched off, and by the
     * gateway's own unit-test constructor, which exercises claim handling rather than revocation.
     */
    TokenRevocationChecker DISABLED = jti -> Mono.just(Boolean.FALSE);

    /**
     * @param jti the {@code jti} claim of a token that has already passed signature, issuer,
     *     audience and expiry validation
     * @return {@code true} if the token is known to be revoked; {@code false} if it is not, or if
     *     that could not be determined
     */
    Mono<Boolean> isRevoked(String jti);
}
