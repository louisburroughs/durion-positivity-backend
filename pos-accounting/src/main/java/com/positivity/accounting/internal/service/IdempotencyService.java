package com.positivity.accounting.internal.service;

import java.util.UUID;

public interface IdempotencyService {

    /**
     * Whether a posting under this key has already been recorded. A key never expires (#2595): a fact redelivered
     * under a fresh envelope id days later posts nothing twice.
     *
     * @return true if the key has been processed before
     */
    boolean isKeyProcessed(String keyValue);

    /**
     * Register a new idempotency key, in the posting's transaction so the key and the entry commit together.
     */
    void registerKey(String keyValue, UUID invoiceId);
}
