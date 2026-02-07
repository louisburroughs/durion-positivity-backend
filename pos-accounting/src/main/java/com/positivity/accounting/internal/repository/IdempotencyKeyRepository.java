package com.positivity.accounting.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.positivity.accounting.internal.entity.IdempotencyKey;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for IdempotencyKey entities.
 */
@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    /**
     * Find an idempotency key by its value.
     */
    Optional<IdempotencyKey> findByKeyValue(String keyValue);

    /**
     * Delete expired idempotency keys.
     */
    @Modifying
    @Query("DELETE FROM IdempotencyKey i WHERE i.expiresAt < :now")
    int deleteExpiredKeys(@Param("now") Instant now);
}
