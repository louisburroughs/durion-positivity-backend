package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.IdempotencyKey;
import com.positivity.accounting.internal.repository.IdempotencyKeyRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing idempotency keys to prevent duplicate processing.
 */
@Service
public class IdempotencyServiceImpl implements IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyServiceImpl.class);

    private final IdempotencyKeyRepository repository;
    private final Clock clock;

    public IdempotencyServiceImpl(IdempotencyKeyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Presence is the whole test (#2595): the 24-hour expiry every key used to carry let a redelivered fact
     * re-post after a day and then fail on the key's unique constraint, and a scheduled cleanup would have let it
     * double-post. A posting key written before V28 still carries an expiry; it is ignored here.
     */
    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean isKeyProcessed(String keyValue) {
        Optional<IdempotencyKey> existing = repository.findByKeyValue(keyValue);
        existing.ifPresent(key ->
                log.info("Idempotency key {} already processed for invoice {}", keyValue, key.getInvoiceId()));
        return existing.isPresent();
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean isKeyActive(String keyValue) {
        Instant now = Instant.now(clock);
        return repository
                .findByKeyValue(keyValue)
                .filter(key -> key.getExpiresAt() == null || key.getExpiresAt().isAfter(now))
                .isPresent();
    }

    @Override
    @Transactional
    public void registerKey(String keyValue, UUID invoiceId) {
        repository.saveAndFlush(new IdempotencyKey(keyValue, invoiceId));
        log.info("Registered idempotency key {} for invoice {}", keyValue, invoiceId);
    }

    @Override
    @Transactional
    public void registerKey(String keyValue, UUID invoiceId, Duration window) {
        IdempotencyKey key = new IdempotencyKey(keyValue, invoiceId);
        key.setExpiresAt(Instant.now(clock).plus(window));
        repository.saveAndFlush(key);
        log.info("Registered idempotency key {} for invoice {} (window {})", keyValue, invoiceId, window);
    }
}
