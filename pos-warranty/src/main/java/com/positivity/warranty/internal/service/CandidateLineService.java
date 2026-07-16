package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.dto.CandidateLine;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Cross-service search of invoices and workorders for origin-line matching (PRD §7 step 2,
 * {@code GET /v1/warranty/claims/candidate-lines?customerId=&vehicleId=&sku=&productEntityId=}).
 * Degrades gracefully when a callee is down — partial results are acceptable.
 */
public interface CandidateLineService {

    /**
     * Find candidate origin lines for the given customer, optionally narrowed by vehicle and
     * by SKU / product ({@code sku} and {@code productEntityId} filters are best-effort:
     * sources that do not carry a product reference are matched by description).
     */
    @NonNull
    List<CandidateLine> findCandidateLines(
            @NonNull UUID customerId, @Nullable UUID vehicleId, @Nullable String sku, @Nullable UUID productEntityId);
}
