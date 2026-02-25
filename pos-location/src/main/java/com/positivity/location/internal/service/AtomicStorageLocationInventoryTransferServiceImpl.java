package com.positivity.location.internal.service;

import com.positivity.location.internal.entity.StorageLocationEntity;
import com.positivity.location.service.StorageLocationInventoryTransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Default atomic transfer strategy using the storage-location repository.
 *
 * Issue: CAP-214 #39
 */
@Service
@RequiredArgsConstructor
public class AtomicStorageLocationInventoryTransferServiceImpl implements StorageLocationInventoryTransferService {

    @Override
    public void transferAll(StorageLocationEntity source, StorageLocationEntity destination) {
        // Inventory ownership moved to pos-inventory; transfer is handled there.
    }
}
