package com.positivity.vehiclefitment.service;

import com.positivity.vehiclefitment.internal.dto.CreateHintRequest;
import com.positivity.vehiclefitment.internal.dto.FilterProductsResponse;
import com.positivity.vehiclefitment.internal.dto.HintResponse;
import com.positivity.vehiclefitment.internal.dto.UpdateHintRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface VehicleApplicabilityHintService {

    /**
     * Create a new vehicle applicability hint for a product.
     */
    HintResponse createHint(CreateHintRequest request);

    /**
     * Update an existing vehicle applicability hint.
     */
    HintResponse updateHint(UUID hintId, UpdateHintRequest request);

    /**
     * Delete a vehicle applicability hint.
     */
    void deleteHint(UUID hintId);

    /**
     * Get a vehicle applicability hint by ID.
     */
    HintResponse getHint(UUID hintId);

    /**
     * Get all hints for a specific product.
     */
    List<HintResponse> getHintsByProductId(UUID productId);

    /**
     * Filter products by vehicle attributes.
     * A product matches if its hints contain tags that are compatible with all
     * provided attributes.
     */
    FilterProductsResponse filterProductsByVehicleAttributes(Map<String, String> vehicleAttributes);
}
