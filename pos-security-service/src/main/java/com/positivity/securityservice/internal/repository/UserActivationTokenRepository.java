package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.UserActivationToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code user_activation_tokens}: a global table, readable with no tenant bound. */
public interface UserActivationTokenRepository extends JpaRepository<UserActivationToken, UUID> {

    Optional<UserActivationToken> findByTokenHash(@NonNull String tokenHash);

    /** Tokens still open for the user; minting a new one closes these. */
    @NonNull
    List<UserActivationToken> findByTenantIdAndUserIdAndUsedAtIsNull(@NonNull UUID tenantId, @NonNull UUID userId);

    /**
     * Consumes the token exactly once: the update only lands while {@code used_at} is still
     * {@code null} and {@code expires_at} is still ahead of {@code now}, so two concurrent
     * activations with the same token cannot both succeed and a token that expires between the
     * service's check and this update is not consumed either.
     *
     * @return 1 when this call consumed the token, 0 when it was already used or has expired
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserActivationToken t SET t.usedAt = :now"
            + " WHERE t.id = :id AND t.usedAt IS NULL AND t.expiresAt > :now")
    int consume(@Param("id") @NonNull UUID id, @Param("now") @NonNull Instant now);
}
