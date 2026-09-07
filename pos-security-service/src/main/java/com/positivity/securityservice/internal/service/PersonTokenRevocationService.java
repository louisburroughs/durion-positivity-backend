package com.positivity.securityservice.internal.service;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Revokes the live tokens of every security user linked to a pos-people person (ADR-0061 §4,
 * #1874). Driven by the staffing-assignment projection when a change narrows the person's reach.
 */
public interface PersonTokenRevocationService {

    /**
     * Revokes every unexpired token pair held by the users whose {@code personId} matches, through
     * the existing {@code TokenRevocationManager} + {@code jwt_token} path: the access and refresh
     * JTIs are written to Redis and the {@code jwt_token} row is deleted, exactly as
     * {@code JwtService.revokeAllTokensForUser} does. Rows whose access token has already expired
     * are left alone.
     *
     * <p>Fail-open on Redis: if Redis is unavailable the rows are still deleted, a metric and a
     * WARN record the degraded state, and the call completes normally.
     *
     * @param personId the pos-people person whose users' tokens are revoked
     * @return the number of {@code jwt_token} rows revoked (0 when the person has no user or no
     *     live token)
     */
    int revokeLiveTokens(@NonNull UUID personId);
}
