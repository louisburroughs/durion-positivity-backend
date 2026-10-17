package com.positivity.accounting.internal.service;

import java.time.Duration;
import java.util.UUID;

public interface IdempotencyService {

    /**
     * Whether a posting under this key has already been recorded. A posting key never expires (#2595): a fact
     * redelivered under a fresh envelope id days later posts nothing twice, whatever expiry an older row carries.
     *
     * @return true if the key has been processed before
     */
    boolean isKeyProcessed(String keyValue);

    /**
     * Whether a windowed key (REST submission dedup, {@link #registerKey(String, UUID, Duration)}) is still within its
     * window: present and not yet expired. A key registered without a window never expires.
     */
    boolean isKeyActive(String keyValue);

    /**
     * Register a posting key that never expires, in the posting's transaction so the key and the entry commit
     * together.
     */
    void registerKey(String keyValue, UUID invoiceId);

    /**
     * Register a windowed key: the REST submission's content-hash dedup (#2207) deliberately accepts an identical
     * submission again once its 24-hour window has passed.
     */
    void registerKey(String keyValue, UUID invoiceId, Duration window);
}
