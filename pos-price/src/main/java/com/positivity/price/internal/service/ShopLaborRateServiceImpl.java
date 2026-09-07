package com.positivity.price.internal.service;

import com.positivity.price.service.ShopLaborRateService;
import com.positivity.price.service.model.LaborRateQuoteRequest;
import com.positivity.price.service.model.LaborRateQuoteResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Grant-surface implementation of {@link ShopLaborRateService} (#1575 Tier 0, T0-3;
 * ADR-0044 amendment 2026-09-07).
 *
 * <p>A pass-through by design, mirroring pos-catalog's {@code ServiceLaborTimeServiceImpl}: all
 * logic lives in {@link LaborRateResolutionService}, and this class exists so the granted
 * interface carries no {@code internal.*} types (ADR-0026 D4).
 */
@Service
@RequiredArgsConstructor
public class ShopLaborRateServiceImpl implements ShopLaborRateService {

    private final LaborRateResolutionService resolutionService;

    @Override
    @NonNull
    public LaborRateQuoteResponse resolveLaborRate(@NonNull LaborRateQuoteRequest request) {
        return resolutionService.resolve(request);
    }
}
