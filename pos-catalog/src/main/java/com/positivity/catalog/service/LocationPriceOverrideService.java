package com.positivity.catalog.service;

import java.util.UUID;

import com.positivity.catalog.internal.dto.EffectiveLocationPriceResponseDto;
import com.positivity.catalog.internal.dto.GuardrailPolicyUpsertRequestDto;
import com.positivity.catalog.internal.dto.LocationPriceOverrideCreateRequestDto;
import com.positivity.catalog.internal.dto.LocationPriceOverrideDecisionRequestDto;
import com.positivity.catalog.internal.dto.LocationPriceOverrideResponseDto;

public interface LocationPriceOverrideService {

    LocationPriceOverrideResponseDto upsertLocationGuardrailPolicy(
            GuardrailPolicyUpsertRequestDto request);

    LocationPriceOverrideResponseDto createOverride(LocationPriceOverrideCreateRequestDto request);

    EffectiveLocationPriceResponseDto getEffectivePrice(UUID locationId, UUID productId);

    LocationPriceOverrideResponseDto approveOverride(UUID overrideId,
            LocationPriceOverrideDecisionRequestDto request);

    LocationPriceOverrideResponseDto rejectOverride(UUID overrideId,
            LocationPriceOverrideDecisionRequestDto request);

}