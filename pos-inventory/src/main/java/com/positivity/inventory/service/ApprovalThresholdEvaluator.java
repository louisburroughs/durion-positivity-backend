package com.positivity.inventory.service;

import com.positivity.inventory.internal.model.cyclecount.ApprovalTier;
import com.positivity.inventory.internal.model.cyclecount.CycleCountAdjustment;

import java.util.Optional;

/**
 * Evaluates cycle count adjustments against approval thresholds.
 */
public interface ApprovalThresholdEvaluator {

    /**
     * Evaluates whether an adjustment requires approval and determines the required tier.
     *
     * @param adjustment the adjustment to evaluate
     * @return optional approval tier, empty if auto-approval is allowed
     */
    Optional<ApprovalTier> evaluateRequiredApprovalTier(CycleCountAdjustment adjustment);
}
