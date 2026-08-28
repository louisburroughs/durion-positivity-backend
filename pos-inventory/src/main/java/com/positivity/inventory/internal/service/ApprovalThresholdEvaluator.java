package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.CycleCountAdjustment;
import com.positivity.inventory.internal.enums.ApprovalTier;
import java.math.BigDecimal;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * Evaluates inventory approval workflows against their threshold configurations.
 */
public interface ApprovalThresholdEvaluator {

    /**
     * Evaluates whether an adjustment requires approval and determines the required
     * tier.
     *
     * @param adjustment the adjustment to evaluate
     * @return optional approval tier, empty if auto-approval is allowed
     */
    Optional<ApprovalTier> evaluateRequiredApprovalTier(CycleCountAdjustment adjustment);

    /**
     * Evaluates a scrap document's monetary value (quantity × cost snapshot)
     * against the scrap flow's value-based thresholds (odoo-parity D1, issue
     * #1030). Unit and percentage thresholds do not apply to scraps.
     *
     * @param scrapValue absolute monetary value of the scrap
     * @return optional approval tier, empty if auto-approval is allowed
     */
    Optional<ApprovalTier> evaluateScrapApprovalTier(@NonNull BigDecimal scrapValue);

    /**
     * Evaluates a manual cost-revaluation's absolute inventory value delta
     * (|Δunit-cost| × on-hand) against the revaluation flow's value-based
     * thresholds (odoo-parity J4, issue #1054). Unit and percentage thresholds
     * do not apply to revaluations.
     *
     * @param absValueDelta absolute inventory value change the revaluation causes
     * @return optional approval tier, empty if auto-approval is allowed
     */
    Optional<ApprovalTier> evaluateRevaluationApprovalTier(@NonNull BigDecimal absValueDelta);
}
