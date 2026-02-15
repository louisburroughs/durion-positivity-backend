package com.positivity.workorder.service;

import com.positivity.workorder.internal.entity.IdempotencyKey;
import com.positivity.workorder.internal.repository.IdempotencyKeyRepository;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing idempotency keys to prevent duplicate workorder promotion processing.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Duration KEY_EXPIRATION = Duration.ofHours(24);

    private final IdempotencyKeyRepository repository;

    public IdempotencyService(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * Check if an idempotency key exists and is valid.
     * 
     * @param keyValue The idempotency key value to check
     * @return The workorder ID if the key has been processed before and is still valid, empty otherwise
     */
    @Transactional(readOnly = true)
    public Optional<UUID> getProcessedWorkorderId(@NonNull String keyValue) {
        Optional<IdempotencyKey> existing = repository.findByKeyValue(keyValue);
        if (existing.isPresent()) {
            IdempotencyKey key = existing.get();
            if (key.getExpiresAt().isAfter(Instant.now())) {
                log.info("Idempotency key {} already processed for workorder {}", keyValue, key.getWorkorderId());
                return Optional.of(key.getWorkorderId());
            }
        }
        return Optional.empty();
    }

    /**
     * Register a new idempotency key.
     * 
     * @param keyValue The idempotency key value
     * @param workorderId The workorder ID that was created
     */
    @Transactional
    public void registerKey(@NonNull String keyValue, @NonNull UUID workorderId) {
        Instant expiresAt = Instant.now().plus(KEY_EXPIRATION);
        IdempotencyKey key = new IdempotencyKey(keyValue, workorderId, expiresAt);
        repository.save(key);
        log.info("Registered idempotency key {} for workorder {}", keyValue, workorderId);
    }

    /**
     * Clean up expired idempotency keys.
     * 
     * @return The number of expired keys deleted
     */
    @Transactional
    public int cleanupExpiredKeys() {
        int deleted = repository.deleteExpiredKeys(Instant.now());
        log.info("Deleted {} expired idempotency keys", deleted);
        return deleted;
    }
}
