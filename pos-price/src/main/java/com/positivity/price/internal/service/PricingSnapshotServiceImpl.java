package com.positivity.price.internal.service;

import com.positivity.price.internal.dto.PricingSnapshotCreateRequest;
import com.positivity.price.internal.exception.SnapshotNotFoundException;
import com.positivity.price.internal.model.PricingSnapshot;
import com.positivity.price.internal.repository.PricingSnapshotRepository;
import com.positivity.price.service.PricingSnapshotService;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation for immutable pricing snapshot storage and retrieval.
 *
 * Issue: #50
 */
@Service
public class PricingSnapshotServiceImpl implements PricingSnapshotService {

    private final PricingSnapshotRepository pricingSnapshotRepository;

    public PricingSnapshotServiceImpl(PricingSnapshotRepository pricingSnapshotRepository) {
        this.pricingSnapshotRepository = pricingSnapshotRepository;
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public PricingSnapshot getSnapshot(@NonNull UUID snapshotId) {
        return pricingSnapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
    }

    @Override
    @NonNull
    @Transactional
    public UUID createSnapshot(@NonNull PricingSnapshotCreateRequest request) {
        PricingSnapshot snapshot = new PricingSnapshot();
        snapshot.setSourceContext(request.getSourceContext());
        snapshot.setItemIdentifier(request.getItemIdentifier());
        snapshot.setQuantity(request.getQuantity());
        snapshot.setPrices(request.getPrices());
        snapshot.setAppliedRules(request.getAppliedRules());
        snapshot.setPolicyVersion(request.getPolicyVersion());

        PricingSnapshot persisted = pricingSnapshotRepository.save(snapshot);
        return persisted.getSnapshotId();
    }
}
