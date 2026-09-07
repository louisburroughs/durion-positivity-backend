package com.positivity.price.internal.service;

import com.positivity.price.internal.dto.LaborRateAdjustmentRequest;
import com.positivity.price.internal.dto.LaborRateAdjustmentResponse;
import com.positivity.price.internal.dto.LaborRateRequest;
import com.positivity.price.internal.dto.LaborRateResponse;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** Authoring surface for shop labor rates and the labor matrix (#1575 Tier 0, T0-3). */
public interface LaborRateAdminService {

    @NonNull
    LaborRateResponse createRate(@NonNull LaborRateRequest request);

    /**
     * Stores the rate if its scope and start instant are not already taken, and returns the row
     * that holds them either way.
     *
     * <p>Idempotent rather than editing, which is what lets a fixture pack be re-run
     * (docs/DATA_SEED_STRATEGY.md §5.3) without contradicting the append-only rule above: a rate
     * that has priced an invoice is never rewritten, so a second load of the same row is a no-op
     * and a changed rate is a new window with a new start. The existing row is returned as it
     * stands, so a caller can see that its value differs from the one submitted.
     */
    @NonNull
    LaborRateResponse upsertRate(@NonNull LaborRateRequest request);

    @NonNull
    List<LaborRateResponse> listRates();

    @NonNull
    LaborRateAdjustmentResponse createAdjustment(@NonNull LaborRateAdjustmentRequest request);

    /** The matrix-step counterpart of {@link #upsertRate}, keyed by scope, code and start instant. */
    @NonNull
    LaborRateAdjustmentResponse upsertAdjustment(@NonNull LaborRateAdjustmentRequest request);

    @NonNull
    List<LaborRateAdjustmentResponse> listAdjustments();
}
