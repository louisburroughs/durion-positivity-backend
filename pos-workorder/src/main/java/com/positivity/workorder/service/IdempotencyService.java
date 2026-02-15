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
 * Service for managing idempotency keys to prevent duplicate workorder
 * processing.
 * 
 * <p>
 * This service provides methods to check if a request has been processed before
 * (based on idempotency key), register new keys, and clean up expired keys.
 * Idempotency keys expire after 24 hours.
 * </p>
 * 
 * <p>
 * <strong>Usage pattern:</strong>
 * </p>
 * 
 * <pre>{@code
 * // In controller or service:
 * Optional<UUID> existing = idempotencyService.getExistingWorkorderId(idempotencyKey);
 * if (existing.isPresent()) {
 *     return existing.get(); // Return existing workorder
 * }
 * 
 * // Create new workorder
 * Workorder workorder = createWorkorderInternal(...);
 * 
 * // Register the idempotency key
 * idempotencyService.registerKey(idempotencyKey, workorder.getId());
 * 
 * return workorder;
 * }</pre>
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
     * Check if an idempotency key exists and return the associated workorder ID.
     * 
     * @param keyValue the idempotency key to check
     * @return Optional containing the workorder ID if the key has been processed
     *         before, empty otherwise
     */
    @Transactional(readOnly = true)
    public Optional<UUID> getExistingWorkorderId(@NonNull String keyValue) {
        Optional<IdempotencyKey> existing = repository.findByKeyValue(keyValue);
        if (existing.isPresent()) {
            IdempotencyKey key = existing.get();
            if (key.getExpiresAt().isAfter(Instant.now())) {
                log.info("Idempotency key {} already processed for workorder {}", keyValue, key.getWorkorderId());
                return Optional.of(key.getWorkorderId());
            } else {
                log.info("Idempotency key {} has expired", keyValue);
            }
        }
        return Optional.empty();
    }

    /**
     * Check if an idempotency key exists and return the associated change request ID.
     * 
     * @param keyValue the idempotency key to check
     * @return Optional containing the change request ID if the key has been processed
     *         before, empty otherwise
     */
    @Transactional(readOnly = true)
    public Optional<UUID> getExistingChangeRequestId(@NonNull String keyValue) {
        Optional<IdempotencyKey> existing = repository.findByKeyValue(keyValue);
        if (existing.isPresent()) {
            IdempotencyKey key = existing.get();
            if (key.getExpiresAt().isAfter(Instant.now()) && key.getChangeRequestId() != null) {
                log.info("Idempotency key {} already processed for change request {}", keyValue, key.getChangeRequestId());
                return Optional.of(key.getChangeRequestId());
            } else if (key.getExpiresAt().isBefore(Instant.now())) {
                log.info("Idempotency key {} has expired", keyValue);
            }
        }
        return Optional.empty();
    }

    /**
     * Check if an idempotency key exists and return the associated labor entry ID.
     * 
     * @param keyValue the idempotency key to check
     * @return Optional containing the labor entry ID if the key has been processed
     *         before, empty otherwise
     */
    @Transactional(readOnly = true)
    public Optional<UUID> getExistingLaborEntryId(@NonNull String keyValue) {
        Optional<IdempotencyKey> existing = repository.findByKeyValue(keyValue);
        if (existing.isPresent()) {
            IdempotencyKey key = existing.get();
            if (key.getExpiresAt().isAfter(Instant.now()) && key.getLaborEntryId() != null) {
                log.info("Idempotency key {} already processed for labor entry {}", keyValue, key.getLaborEntryId());
                return Optional.of(key.getLaborEntryId());
            } else if (key.getExpiresAt().isBefore(Instant.now())) {
                log.info("Idempotency key {} has expired", keyValue);
            }
        }
        return Optional.empty();
    }

    /**
     * Register a new idempotency key associated with a workorder.
     * 
     * @param keyValue    the idempotency key value
     * @param workorderId the ID of the created workorder
     */
    @Transactional
    public void registerKey(@NonNull String keyValue, @NonNull UUID workorderId) {
        Instant expiresAt = Instant.now().plus(KEY_EXPIRATION);
        IdempotencyKey key = new IdempotencyKey(keyValue, workorderId, expiresAt);
        repository.save(key);
        log.info("Registered idempotency key {} for workorder {}", keyValue, workorderId);
    }

    /**
     * Mark an idempotency key as processed for a change request.
     * 
     * @param keyValue        the idempotency key value
     * @param changeRequestId the ID of the created change request
     */
    @Transactional
    public void markKeyProcessedForChangeRequest(@NonNull String keyValue, @NonNull UUID changeRequestId) {
        Instant expiresAt = Instant.now().plus(KEY_EXPIRATION);
        IdempotencyKey key = new IdempotencyKey(keyValue, null, changeRequestId, expiresAt);
        repository.save(key);
        log.info("Registered idempotency key {} for change request {}", keyValue, changeRequestId);
    }

    /**
     * Register a new idempotency key associated with a labor entry.
     * 
     * @param keyValue     the idempotency key value
     * @param laborEntryId the ID of the created labor entry
     */
    @Transactional
    public void registerLaborKey(@NonNull String keyValue, @NonNull UUID laborEntryId) {
        Instant expiresAt = Instant.now().plus(KEY_EXPIRATION);
        IdempotencyKey key = new IdempotencyKey();
        key.setKeyValue(keyValue);
        key.setLaborEntryId(laborEntryId);
        key.setCreatedAt(Instant.now());
        key.setExpiresAt(expiresAt);
        repository.save(key);
        log.info("Registered idempotency key {} for labor entry {}", keyValue, laborEntryId);
    }

    /**
     * Check if an idempotency key exists and return the associated part usage event ID.
     * 
     * @param keyValue the idempotency key to check
     * @return Optional containing the part usage event ID if the key has been processed
     *         before, empty otherwise
     */
    @Transactional(readOnly = true)
    public Optional<UUID> getExistingPartUsageEventId(@NonNull String keyValue) {
        Optional<IdempotencyKey> existing = repository.findByKeyValue(keyValue);
        if (existing.isPresent()) {
            IdempotencyKey key = existing.get();
            if (key.getExpiresAt().isAfter(Instant.now()) && key.getPartUsageEventId() != null) {
                log.info("Idempotency key {} already processed for part usage event {}", keyValue, key.getPartUsageEventId());
                return Optional.of(key.getPartUsageEventId());
            } else if (key.getExpiresAt().isBefore(Instant.now())) {
                log.info("Idempotency key {} has expired", keyValue);
            }
        }
        return Optional.empty();
    }

    /**
     * Mark an idempotency key as processed for a part usage event.
     * 
     * @param keyValue        the idempotency key value
     * @param partUsageEventId the ID of the created part usage event
     */
    @Transactional
    public void markKeyProcessedForPartUsage(@NonNull String keyValue, @NonNull UUID partUsageEventId) {
        Instant expiresAt = Instant.now().plus(KEY_EXPIRATION);
        IdempotencyKey key = new IdempotencyKey();
        key.setKeyValue(keyValue);
        key.setPartUsageEventId(partUsageEventId);
        key.setCreatedAt(Instant.now());
        key.setExpiresAt(expiresAt);
        repository.save(key);
        log.info("Registered idempotency key {} for part usage event {}", keyValue, partUsageEventId);
    }

    /**
     * Clean up expired idempotency keys.
     * 
     * <p>
     * This method should be called periodically (e.g., via a scheduled task)
     * to remove old keys and prevent unbounded table growth.
     * </p>
     * 
     * @return count of deleted keys
     */
    @Transactional
    public int cleanupExpiredKeys() {
        int deleted = repository.deleteExpiredKeys(Instant.now());
        log.info("Deleted {} expired idempotency keys", deleted);
        return deleted;
    }
}
