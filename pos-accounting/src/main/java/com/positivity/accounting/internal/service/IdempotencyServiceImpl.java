package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.IdempotencyKey;
import com.positivity.accounting.internal.repository.IdempotencyKeyRepository;
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

    public IdempotencyServiceImpl(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Presence is the whole test (#2595): the 24-hour expiry the key used to carry let a redelivery after a day
     * re-post and then fail on the key's unique constraint, and a scheduled cleanup would have let it double-post.
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
    @Transactional
    public void registerKey(String keyValue, UUID invoiceId) {
        IdempotencyKey key = new IdempotencyKey(keyValue, invoiceId);
        repository.saveAndFlush(key);
        log.info("Registered idempotency key {} for invoice {}", keyValue, invoiceId);
    }
}
