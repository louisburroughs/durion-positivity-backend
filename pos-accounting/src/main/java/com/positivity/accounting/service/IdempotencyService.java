package com.positivity.accounting.service;

import java.util.UUID;

public interface IdempotencyService {

    /**
     * Check if an idempotency key exists and is valid.
     * 
     * @return true if the key has been processed before
     */
    boolean isKeyProcessed(String keyValue);

    /**
     * Register a new idempotency key.
     */
    void registerKey(String keyValue, UUID invoiceId);

    /**
     * Clean up expired idempotency keys.
     */
    int cleanupExpiredKeys();

}