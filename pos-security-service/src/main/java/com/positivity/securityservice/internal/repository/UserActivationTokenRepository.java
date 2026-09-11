package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.UserActivationToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code user_activation_tokens}: a global table, readable with no tenant bound. */
public interface UserActivationTokenRepository extends JpaRepository<UserActivationToken, UUID> {

    Optional<UserActivationToken> findByTokenHash(String tokenHash);

    /** Tokens still open for the user; minting a new one closes these. */
    List<UserActivationToken> findByTenantIdAndUserIdAndUsedAtIsNull(UUID tenantId, UUID userId);

    /**
     * Consumes the token exactly once: the update only lands while {@code used_at} is still
     * {@code null}, so two concurrent activations with the same token cannot both succeed.
     *
     * @return 1 when this call consumed the token, 0 when it was already used
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserActivationToken t SET t.usedAt = :now WHERE t.id = :id AND t.usedAt IS NULL")
    int consume(@Param("id") UUID id, @Param("now") Instant now);
}
