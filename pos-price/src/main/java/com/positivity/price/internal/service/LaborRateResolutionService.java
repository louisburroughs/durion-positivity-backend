package com.positivity.price.internal.service;

import com.positivity.price.service.model.LaborRateQuoteRequest;
import com.positivity.price.service.model.LaborRateQuoteResponse;
import org.jspecify.annotations.NonNull;

/**
 * Resolves the hourly labor rate in force for a scope, then applies the shop's labor matrix
 * (#1575 Tier 0, T0-3). Misses are typed, never thrown.
 */
public interface LaborRateResolutionService {

    @NonNull
    LaborRateQuoteResponse resolve(@NonNull LaborRateQuoteRequest request);
}
