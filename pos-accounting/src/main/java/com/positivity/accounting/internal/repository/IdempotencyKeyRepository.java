package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.IdempotencyKey;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for IdempotencyKey entities. Keys are never deleted (#2595): a posting key is the durable guard
 * against a fact redelivered under a fresh envelope id.
 */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    /**
     * Find an idempotency key by its value.
     */
    Optional<IdempotencyKey> findByKeyValue(String keyValue);
}
