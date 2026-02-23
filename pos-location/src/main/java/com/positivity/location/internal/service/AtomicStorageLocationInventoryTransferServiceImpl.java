package com.positivity.location.internal.service;

import com.positivity.location.internal.entity.StorageLocationEntity;
import com.positivity.location.internal.repository.StorageLocationRepository;
import com.positivity.location.service.StorageLocationInventoryTransferService;
import java.util.List;
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

    private final StorageLocationRepository storageLocationRepository;

    @Override
    public void transferAll(StorageLocationEntity source, StorageLocationEntity destination) {
        destination.setInventoryCount(destination.getInventoryCount() + source.getInventoryCount());
        source.setInventoryCount(0);
        storageLocationRepository.saveAll(List.of(source, destination));
    }
}
