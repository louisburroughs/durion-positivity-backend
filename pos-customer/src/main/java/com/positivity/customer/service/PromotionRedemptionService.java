package com.positivity.customer.service;

import com.positivity.customer.internal.dto.PromotionRedemptionResponse;
import com.positivity.customer.internal.dto.RecordRedemptionRequest;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.UUID;

/** Manages promotion redemption recording for the CRM domain. Issue: #94 */
public interface PromotionRedemptionService {

    /**
     * Record a promotion redemption idempotently. Duplicate
     * (promotionId+workorderId) throws DuplicateRedemptionException resulting in
     * 409. Issue: #94
     */
    @NonNull
    PromotionRedemptionResponse recordRedemption(@NonNull RecordRedemptionRequest request);

    /** Get all redemptions for a customer. Issue: #94 */
    @NonNull
    List<PromotionRedemptionResponse> getRedemptionsByCustomer(@NonNull UUID customerId);
}