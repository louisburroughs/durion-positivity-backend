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

    @NonNull
    List<LaborRateResponse> listRates();

    @NonNull
    LaborRateAdjustmentResponse createAdjustment(@NonNull LaborRateAdjustmentRequest request);

    @NonNull
    List<LaborRateAdjustmentResponse> listAdjustments();
}
