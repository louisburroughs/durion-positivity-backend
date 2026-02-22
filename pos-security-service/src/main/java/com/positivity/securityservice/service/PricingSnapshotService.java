package com.positivity.securityservice.service;

import com.positivity.securityservice.internal.dto.PricingSnapshotDto;
import com.positivity.securityservice.internal.dto.PricingSnapshotRequest;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Public API for immutable pricing snapshot persistence and retrieval.
 *
 * Issue: #41
 */
public interface PricingSnapshotService {

    PricingSnapshotDto createSnapshot(@NonNull PricingSnapshotRequest request);

    PricingSnapshotDto getSnapshot(@NonNull UUID snapshotId);
}
