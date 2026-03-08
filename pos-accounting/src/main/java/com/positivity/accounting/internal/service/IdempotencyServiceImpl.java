package com.positivity.accounting.internal.service;

import java.time.Clock;

import com.positivity.accounting.internal.entity.IdempotencyKey;
import com.positivity.accounting.internal.repository.IdempotencyKeyRepository;
import com.positivity.accounting.service.IdempotencyService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing idempotency keys to prevent duplicate processing.
 */
@Service
public class IdempotencyServiceImpl implements IdempotencyService {
    private final Clock clock;

    private static final Logger log = LoggerFactory.getLogger(IdempotencyServiceImpl.class);
    private static final Duration KEY_EXPIRATION = Duration.ofHours(24);

    private final IdempotencyKeyRepository repository;

    public IdempotencyServiceImpl(IdempotencyKeyRepository repository, Clock clock) {
        this.clock = clock;
        this.repository = repository;
    }

    /**
     * Check if an idempotency key exists and is valid.
     *
     * <p>
     * Uses {@code SUPPORTS} propagation to avoid overriding flush mode when called
     * from within an existing write transaction.
     *
     * @return true if the key has been processed before
     */
    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean isKeyProcessed(String keyValue) {
        Optional<IdempotencyKey> existing = repository.findByKeyValue(keyValue);
        if (existing.isPresent()) {
            IdempotencyKey key = existing.get();
            if (key.getExpiresAt().isAfter(Instant.now(clock))) {
                log.info("Idempotency key {} already processed for invoice {}", keyValue, key.getInvoiceId());
                return true;
            }
        }
        return false;
    }

    /**
     * Register a new idempotency key.
     *
     * <p>
     * Uses {@code saveAndFlush} to ensure the key is immediately visible to
     * subsequent DB queries within the same transaction.
     */
    @Override
    @Transactional
    public void registerKey(String keyValue, UUID invoiceId) {
        Instant expiresAt = Instant.now(clock).plus(KEY_EXPIRATION);
        IdempotencyKey key = new IdempotencyKey(keyValue, invoiceId, expiresAt);
        repository.saveAndFlush(key);
        log.info("Registered idempotency key {} for invoice {}", keyValue, invoiceId);
    }

    /**
     * Clean up expired idempotency keys.
     */
    @Override
    @Transactional
    public int cleanupExpiredKeys() {
        int deleted = repository.deleteExpiredKeys(Instant.now(clock));
        log.info("Deleted {} expired idempotency keys", deleted);
        return deleted;
    }
}
