package com.positivity.inventory.service;

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
}
