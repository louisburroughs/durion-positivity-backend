package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.JwtToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JwtTokenRepository extends JpaRepository<JwtToken, UUID> {
    Optional<JwtToken> findByToken(String token);

    void deleteByToken(String token);

    Optional<JwtToken> findByRefreshToken(String refreshToken);

    void deleteByRefreshToken(String refreshToken);

    List<JwtToken> findAllBySubject(String subject);

    /** Token pairs of {@code subject} whose access token has not yet expired at {@code now}. */
    List<JwtToken> findAllBySubjectAndExpiresAtAfter(String subject, Instant now);

    /**
     * Impersonation tokens minted by {@code impersonatedByUserId} in the tenant currently bound
     * (ADR-0062 §7, WS2b-4). The subject of such a row is synthetic and its tenant is the target
     * tenant, so the operator's user id is the only key that reaches it.
     */
    List<JwtToken> findAllByImpersonatedByUserId(UUID impersonatedByUserId);
}
