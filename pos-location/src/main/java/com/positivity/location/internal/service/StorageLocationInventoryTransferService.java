package com.positivity.location.internal.service;

import com.positivity.location.internal.entity.StorageLocationEntity;

/**
 * Executes stock transfer between storage locations.
 *
 * Issue: CAP-214 #39
 */
public interface StorageLocationInventoryTransferService {

    /**
     * Transfers all inventory from source to destination in one operation.
     *
     * @param source      source location
     * @param destination destination location
     */
    void transferAll(StorageLocationEntity source, StorageLocationEntity destination);
}
