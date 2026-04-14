package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.IdempotencyKey;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for IdempotencyKey entities.
 *
 * <p>
 * Provides data access methods for idempotency key management,
 * including lookup by key value and cleanup of expired keys.
 * </p>
 */
@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    /**
     * Find an idempotency key by its value.
     *
     * @param keyValue the idempotency key value to search for
     * @return Optional containing the IdempotencyKey if found
     */
    Optional<IdempotencyKey> findByKeyValue(String keyValue);

    /**
     * Delete expired idempotency keys.
     *
     * @param now the current timestamp; keys with expiresAt before this are deleted
     * @return count of deleted keys
     */
    @Modifying
    @Query("DELETE FROM IdempotencyKey i WHERE i.expiresAt < :now")
    int deleteExpiredKeys(@Param("now") Instant now);
}
