package com.positivity.price.service;

import com.positivity.price.internal.dto.PricingSnapshotCreateRequest;
import com.positivity.price.internal.dto.PricingSnapshotResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Service contract for immutable pricing snapshots.
 *
 * Issue: #50
 */
public interface PricingSnapshotService {

    /**
     * Retrieves an immutable pricing snapshot.
     *
     * @param snapshotId snapshot identifier
     * @return snapshot response
     */
    @NonNull
    PricingSnapshotResponse getSnapshot(@NonNull UUID snapshotId);

    /**
     * Creates a new immutable pricing snapshot.
     *
     * @param request snapshot create payload
     * @return generated snapshot identifier
     */
    @NonNull
    UUID createSnapshot(@NonNull PricingSnapshotCreateRequest request);
}
