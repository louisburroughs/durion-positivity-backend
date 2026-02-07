package com.positivity.accounting.service;

import com.positivity.accounting.internal.entity.IdempotencyKey;
import com.positivity.accounting.internal.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing idempotency keys to prevent duplicate processing.
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
     * @return true if the key has been processed before
     */
    @Transactional(readOnly = true)
    public boolean isKeyProcessed(String keyValue) {
        Optional<IdempotencyKey> existing = repository.findByKeyValue(keyValue);
        if (existing.isPresent()) {
            IdempotencyKey key = existing.get();
            if (key.getExpiresAt().isAfter(Instant.now())) {
                log.info("Idempotency key {} already processed for invoice {}", keyValue, key.getInvoiceId());
                return true;
            }
        }
        return false;
    }

    /**
     * Register a new idempotency key.
     */
    @Transactional
    public void registerKey(String keyValue, UUID invoiceId) {
        Instant expiresAt = Instant.now().plus(KEY_EXPIRATION);
        IdempotencyKey key = new IdempotencyKey(keyValue, invoiceId, expiresAt);
        repository.save(key);
        log.info("Registered idempotency key {} for invoice {}", keyValue, invoiceId);
    }

    /**
     * Clean up expired idempotency keys.
     */
    @Transactional
    public int cleanupExpiredKeys() {
        int deleted = repository.deleteExpiredKeys(Instant.now());
        log.info("Deleted {} expired idempotency keys", deleted);
        return deleted;
    }
}
