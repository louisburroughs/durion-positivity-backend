package com.positivity.workorder.internal.service;

import java.time.Clock;

import com.positivity.workorder.internal.entity.IdempotencyKey;
import com.positivity.workorder.internal.repository.IdempotencyKeyRepository;
import com.positivity.workorder.service.IdempotencyService;

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
public class IdempotencyServiceImpl implements IdempotencyService {
    private final Clock clock;

    private static final Logger log = LoggerFactory.getLogger(IdempotencyServiceImpl.class);
    private static final Duration KEY_EXPIRATION = Duration.ofHours(24);
    private static final String EXPIRED_KEY_LOG_MESSAGE = "Idempotency key {} has expired";
    private static final String DEFAULT_WORKORDER_OPERATION = "workorder.default";
    private static final String DEFAULT_CHANGE_REQUEST_OPERATION = "change-request.default";
    private static final String DEFAULT_LABOR_OPERATION = "labor.default";
    private static final String DEFAULT_PART_USAGE_OPERATION = "part-usage.default";
    private static final String DEFAULT_PART_ADJUSTMENT_OPERATION = "part-adjustment.default";
    private static final String DEFAULT_INVOICE_OPERATION = "invoice.default";

    private final IdempotencyKeyRepository repository;

    public IdempotencyServiceImpl(IdempotencyKeyRepository repository, Clock clock) {
        this.clock = clock;
        this.repository = repository;
    }

    /**
     * Check if an idempotency key exists and return the associated workorder ID.
     * 
     * @param keyValue the idempotency key to check
     * @return Optional containing the workorder ID if the key has been processed
     *         before, empty otherwise
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> getExistingWorkorderId(@NonNull String keyValue) {
        return getExistingWorkorderIdInternal(DEFAULT_WORKORDER_OPERATION, keyValue);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> getExistingWorkorderId(@NonNull String operation, @NonNull String keyValue) {
        return getExistingWorkorderIdInternal(operation, keyValue);
    }

    private Optional<UUID> getExistingWorkorderIdInternal(@NonNull String operation, @NonNull String keyValue) {
        String scopedKey = scope(operation, keyValue);
        Optional<IdempotencyKey> existing = repository.findByKeyValue(scopedKey);
        if (existing.isPresent()) {
            IdempotencyKey key = existing.get();
            if (key.getExpiresAt().isAfter(Instant.now(clock))) {
                log.info("Idempotency key {} already processed for workorder {}", scopedKey, key.getWorkorderId());
                return Optional.of(key.getWorkorderId());
            } else {
                log.info(EXPIRED_KEY_LOG_MESSAGE, scopedKey);
            }
        }
        return Optional.empty();
    }

    /**
     * Check if an idempotency key exists and return the associated change request
     * ID.
     * 
     * @param keyValue the idempotency key to check
     * @return Optional containing the change request ID if the key has been
     *         processed
     *         before, empty otherwise
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> getExistingChangeRequestId(@NonNull String keyValue) {
        return getExistingChangeRequestIdInternal(DEFAULT_CHANGE_REQUEST_OPERATION, keyValue);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> getExistingChangeRequestId(@NonNull String operation, @NonNull String keyValue) {
        return getExistingChangeRequestIdInternal(operation, keyValue);
    }

    private Optional<UUID> getExistingChangeRequestIdInternal(@NonNull String operation, @NonNull String keyValue) {
        String scopedKey = scope(operation, keyValue);
        Optional<IdempotencyKey> existing = repository.findByKeyValue(scopedKey);
        if (existing.isPresent()) {
            IdempotencyKey key = existing.get();
            if (key.getExpiresAt().isAfter(Instant.now(clock)) && key.getChangeRequestId() != null) {
                log.info("Idempotency key {} already processed for change request {}", scopedKey,
                        key.getChangeRequestId());
                return Optional.of(key.getChangeRequestId());
            } else if (key.getExpiresAt().isBefore(Instant.now(clock))) {
                log.info(EXPIRED_KEY_LOG_MESSAGE, scopedKey);
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
    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> getExistingLaborEntryId(@NonNull String keyValue) {
        return getExistingLaborEntryIdInternal(DEFAULT_LABOR_OPERATION, keyValue);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> getExistingLaborEntryId(@NonNull String operation, @NonNull String keyValue) {
        return getExistingLaborEntryIdInternal(operation, keyValue);
    }

    private Optional<UUID> getExistingLaborEntryIdInternal(@NonNull String operation, @NonNull String keyValue) {
        String scopedKey = scope(operation, keyValue);
        Optional<IdempotencyKey> existing = repository.findByKeyValue(scopedKey);
        if (existing.isPresent()) {
            IdempotencyKey key = existing.get();
            if (key.getExpiresAt().isAfter(Instant.now(clock)) && key.getLaborEntryId() != null) {
                log.info("Idempotency key {} already processed for labor entry {}", scopedKey, key.getLaborEntryId());
                return Optional.of(key.getLaborEntryId());
            } else if (key.getExpiresAt().isBefore(Instant.now(clock))) {
                log.info(EXPIRED_KEY_LOG_MESSAGE, scopedKey);
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
    @Override
    @Transactional
    public void registerKey(@NonNull String keyValue, @NonNull UUID workorderId) {
        registerKeyInternal(DEFAULT_WORKORDER_OPERATION, keyValue, workorderId);
    }

    @Override
    @Transactional
    public void registerKey(@NonNull String operation, @NonNull String keyValue, @NonNull UUID workorderId) {
        registerKeyInternal(operation, keyValue, workorderId);
    }

    private void registerKeyInternal(@NonNull String operation, @NonNull String keyValue, @NonNull UUID workorderId) {
        Instant expiresAt = Instant.now(clock).plus(KEY_EXPIRATION);
        String scopedKey = scope(operation, keyValue);
        IdempotencyKey key = new IdempotencyKey(scopedKey, workorderId, expiresAt);
        repository.save(key);
        log.info("Registered idempotency key {} for workorder {}", scopedKey, workorderId);
    }

    /**
     * Mark an idempotency key as processed for a change request.
     * 
     * @param keyValue        the idempotency key value
     * @param changeRequestId the ID of the created change request
     */
    @Override
    @Transactional
    public void markKeyProcessedForChangeRequest(@NonNull String keyValue, @NonNull UUID changeRequestId) {
        markKeyProcessedForChangeRequestInternal(DEFAULT_CHANGE_REQUEST_OPERATION, keyValue, changeRequestId);
    }

    @Override
    @Transactional
    public void markKeyProcessedForChangeRequest(@NonNull String operation, @NonNull String keyValue,
            @NonNull UUID changeRequestId) {
        markKeyProcessedForChangeRequestInternal(operation, keyValue, changeRequestId);
        }

        private void markKeyProcessedForChangeRequestInternal(@NonNull String operation, @NonNull String keyValue,
            @NonNull UUID changeRequestId) {
        Instant expiresAt = Instant.now(clock).plus(KEY_EXPIRATION);
        String scopedKey = scope(operation, keyValue);
        IdempotencyKey key = new IdempotencyKey(scopedKey, null, changeRequestId, expiresAt);
        repository.save(key);
        log.info("Registered idempotency key {} for change request {}", scopedKey, changeRequestId);
    }

    /**
     * Register a new idempotency key associated with a labor entry.
     * 
     * @param keyValue     the idempotency key value
     * @param laborEntryId the ID of the created labor entry
     */
    @Override
    @Transactional
    public void registerLaborKey(@NonNull String keyValue, @NonNull UUID laborEntryId) {
        registerLaborKeyInternal(DEFAULT_LABOR_OPERATION, keyValue, laborEntryId);
    }

    @Override
    @Transactional
    public void registerLaborKey(@NonNull String operation, @NonNull String keyValue, @NonNull UUID laborEntryId) {
        registerLaborKeyInternal(operation, keyValue, laborEntryId);
        }

        private void registerLaborKeyInternal(@NonNull String operation, @NonNull String keyValue,
            @NonNull UUID laborEntryId) {
        Instant expiresAt = Instant.now(clock).plus(KEY_EXPIRATION);
        String scopedKey = scope(operation, keyValue);
        IdempotencyKey key = new IdempotencyKey();
        key.setKeyValue(scopedKey);
        key.setLaborEntryId(laborEntryId);
        key.setCreatedAt(Instant.now(clock));
        key.setExpiresAt(expiresAt);
        repository.save(key);
        log.info("Registered idempotency key {} for labor entry {}", scopedKey, laborEntryId);
    }

    /**
     * Check if an idempotency key exists and return the associated part usage event
     * ID.
     * 
     * @param keyValue the idempotency key to check
     * @return Optional containing the part usage event ID if the key has been
     *         processed
     *         before, empty otherwise
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> getExistingPartUsageEventId(@NonNull String keyValue) {
        return getExistingPartUsageEventIdInternal(DEFAULT_PART_USAGE_OPERATION, keyValue);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> getExistingPartUsageEventId(@NonNull String operation, @NonNull String keyValue) {
        return getExistingPartUsageEventIdInternal(operation, keyValue);
    }

    private Optional<UUID> getExistingPartUsageEventIdInternal(@NonNull String operation, @NonNull String keyValue) {
        String scopedKey = scope(operation, keyValue);
        Optional<IdempotencyKey> existing = repository.findByKeyValue(scopedKey);
        if (existing.isPresent()) {
            IdempotencyKey key = existing.get();
            if (key.getExpiresAt().isAfter(Instant.now(clock)) && key.getPartUsageEventId() != null) {
                log.info("Idempotency key {} already processed for part usage event {}", scopedKey,
                        key.getPartUsageEventId());
                return Optional.of(key.getPartUsageEventId());
            } else if (key.getExpiresAt().isBefore(Instant.now(clock))) {
                log.info(EXPIRED_KEY_LOG_MESSAGE, scopedKey);
            }
        }
        return Optional.empty();
    }

    /**
     * Mark an idempotency key as processed for a part usage event.
     * 
     * @param keyValue         the idempotency key value
     * @param partUsageEventId the ID of the created part usage event
     */
    @Override
    @Transactional
    public void markKeyProcessedForPartUsage(@NonNull String keyValue, @NonNull UUID partUsageEventId) {
        markKeyProcessedForPartUsageInternal(DEFAULT_PART_USAGE_OPERATION, keyValue, partUsageEventId);
    }

    @Override
    @Transactional
    public void markKeyProcessedForPartUsage(@NonNull String operation, @NonNull String keyValue,
            @NonNull UUID partUsageEventId) {
        markKeyProcessedForPartUsageInternal(operation, keyValue, partUsageEventId);
        }

        private void markKeyProcessedForPartUsageInternal(@NonNull String operation, @NonNull String keyValue,
            @NonNull UUID partUsageEventId) {
        Instant expiresAt = Instant.now(clock).plus(KEY_EXPIRATION);
        String scopedKey = scope(operation, keyValue);
        IdempotencyKey key = new IdempotencyKey();
        key.setKeyValue(scopedKey);
        key.setPartUsageEventId(partUsageEventId);
        key.setCreatedAt(Instant.now(clock));
        key.setExpiresAt(expiresAt);
        repository.save(key);
        log.info("Registered idempotency key {} for part usage event {}", scopedKey, partUsageEventId);
    }

    /**
     * Check if an idempotency key exists and return the associated part adjustment
     * event ID.
     * 
     * @param keyValue the idempotency key to check
     * @return Optional containing the part adjustment event ID if the key has been
     *         processed
     *         before, empty otherwise
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> getExistingPartAdjustmentEventId(@NonNull String keyValue) {
        return getExistingPartAdjustmentEventIdInternal(DEFAULT_PART_ADJUSTMENT_OPERATION, keyValue);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> getExistingPartAdjustmentEventId(@NonNull String operation, @NonNull String keyValue) {
        return getExistingPartAdjustmentEventIdInternal(operation, keyValue);
        }

        private Optional<UUID> getExistingPartAdjustmentEventIdInternal(@NonNull String operation,
            @NonNull String keyValue) {
        String scopedKey = scope(operation, keyValue);
        Optional<IdempotencyKey> existing = repository.findByKeyValue(scopedKey);
        if (existing.isPresent()) {
            IdempotencyKey key = existing.get();
            if (key.getExpiresAt().isAfter(Instant.now(clock)) && key.getPartAdjustmentEventId() != null) {
                log.info("Idempotency key {} already processed for part adjustment event {}", scopedKey,
                        key.getPartAdjustmentEventId());
                return Optional.of(key.getPartAdjustmentEventId());
            } else if (key.getExpiresAt().isBefore(Instant.now(clock))) {
                log.info(EXPIRED_KEY_LOG_MESSAGE, scopedKey);
            }
        }
        return Optional.empty();
    }

    /**
     * Check if an idempotency key exists and return the associated invoice ID.
     *
     * @param keyValue the idempotency key to check
     * @return Optional containing the invoice ID if the key has been processed
     *         before, empty otherwise
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> getExistingInvoiceId(@NonNull String keyValue) {
        return getExistingInvoiceIdInternal(DEFAULT_INVOICE_OPERATION, keyValue);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> getExistingInvoiceId(@NonNull String operation, @NonNull String keyValue) {
        return getExistingInvoiceIdInternal(operation, keyValue);
    }

    private Optional<UUID> getExistingInvoiceIdInternal(@NonNull String operation, @NonNull String keyValue) {
        String scopedKey = scope(operation, keyValue);
        Optional<IdempotencyKey> existing = repository.findByKeyValue(scopedKey);
        if (existing.isPresent()) {
            IdempotencyKey key = existing.get();
            if (key.getExpiresAt().isAfter(Instant.now(clock)) && key.getInvoiceId() != null) {
                log.info("Idempotency key {} already processed for invoice {}", scopedKey, key.getInvoiceId());
                return Optional.of(key.getInvoiceId());
            } else if (key.getExpiresAt().isBefore(Instant.now(clock))) {
                log.info(EXPIRED_KEY_LOG_MESSAGE, scopedKey);
            }
        }
        return Optional.empty();
    }

    /**
     * Mark an idempotency key as processed for a part adjustment event.
     * 
     * @param keyValue              the idempotency key value
     * @param partAdjustmentEventId the ID of the created part adjustment event
     */
    @Override
    @Transactional
    public void markKeyProcessedForPartAdjustment(@NonNull String keyValue, @NonNull UUID partAdjustmentEventId) {
        markKeyProcessedForPartAdjustmentInternal(DEFAULT_PART_ADJUSTMENT_OPERATION, keyValue, partAdjustmentEventId);
    }

    @Override
    @Transactional
    public void markKeyProcessedForPartAdjustment(@NonNull String operation, @NonNull String keyValue,
            @NonNull UUID partAdjustmentEventId) {
        markKeyProcessedForPartAdjustmentInternal(operation, keyValue, partAdjustmentEventId);
        }

        private void markKeyProcessedForPartAdjustmentInternal(@NonNull String operation, @NonNull String keyValue,
            @NonNull UUID partAdjustmentEventId) {
        Instant expiresAt = Instant.now(clock).plus(KEY_EXPIRATION);
        String scopedKey = scope(operation, keyValue);
        IdempotencyKey key = new IdempotencyKey();
        key.setKeyValue(scopedKey);
        key.setPartAdjustmentEventId(partAdjustmentEventId);
        key.setCreatedAt(Instant.now(clock));
        key.setExpiresAt(expiresAt);
        repository.save(key);
        log.info("Registered idempotency key {} for part adjustment event {}", scopedKey, partAdjustmentEventId);
    }

    /**
     * Mark an idempotency key as processed for invoice generation.
     *
     * @param keyValue  the idempotency key value
     * @param invoiceId the ID of the generated invoice
     */
    @Override
    @Transactional
    public void registerInvoiceKey(@NonNull String keyValue, @NonNull UUID invoiceId) {
        registerInvoiceKeyInternal(DEFAULT_INVOICE_OPERATION, keyValue, invoiceId);
    }

    @Override
    @Transactional
    public void registerInvoiceKey(@NonNull String operation, @NonNull String keyValue, @NonNull UUID invoiceId) {
        registerInvoiceKeyInternal(operation, keyValue, invoiceId);
        }

        private void registerInvoiceKeyInternal(@NonNull String operation, @NonNull String keyValue,
            @NonNull UUID invoiceId) {
        Instant expiresAt = Instant.now(clock).plus(KEY_EXPIRATION);
        String scopedKey = scope(operation, keyValue);
        IdempotencyKey key = new IdempotencyKey();
        key.setKeyValue(scopedKey);
        key.setInvoiceId(invoiceId);
        key.setCreatedAt(Instant.now(clock));
        key.setExpiresAt(expiresAt);
        repository.save(key);
        log.info("Registered idempotency key {} for invoice {}", scopedKey, invoiceId);
    }

    private String scope(@NonNull String operation, @NonNull String keyValue) {
        return operation + ":" + keyValue;
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
    @Override
    @Transactional
    public int cleanupExpiredKeys() {
        int deleted = repository.deleteExpiredKeys(Instant.now(clock));
        log.info("Deleted {} expired idempotency keys", deleted);
        return deleted;
    }
}
