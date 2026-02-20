package com.positivity.price.internal.service;

import com.positivity.price.internal.dto.PricingSnapshotCreateRequest;
import com.positivity.price.internal.dto.PricingSnapshotResponse;
import com.positivity.price.internal.entity.PricingSnapshot;
import com.positivity.price.internal.exception.SnapshotNotFoundException;
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
    public PricingSnapshotResponse getSnapshot(@NonNull UUID snapshotId) {
        PricingSnapshot snapshot = pricingSnapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new SnapshotNotFoundException(snapshotId));

        PricingSnapshotResponse response = new PricingSnapshotResponse();
        response.setSnapshotId(snapshot.getSnapshotId());
        response.setCreatedAt(snapshot.getCreatedAt());
        response.setSourceContext(snapshot.getSourceContext());
        response.setItemIdentifier(snapshot.getItemIdentifier());
        response.setQuantity(snapshot.getQuantity());
        response.setPrices(snapshot.getPrices());
        response.setAppliedRules(snapshot.getAppliedRules());
        response.setPolicyVersion(snapshot.getPolicyVersion());
        return response;
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
