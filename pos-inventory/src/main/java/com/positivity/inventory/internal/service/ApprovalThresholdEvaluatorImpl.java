package com.positivity.inventory.internal.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.positivity.inventory.internal.model.cyclecount.ApprovalThresholdConfig;
import com.positivity.inventory.internal.model.cyclecount.ApprovalTier;
import com.positivity.inventory.internal.model.cyclecount.CycleCountAdjustment;
import com.positivity.inventory.internal.repository.ApprovalThresholdConfigRepository;
import com.positivity.inventory.service.ApprovalThresholdEvaluator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Evaluates cycle count adjustments against approval thresholds.
 *
 * <p>
 * Uses a composite threshold model: approval is required if ANY threshold is
 * exceeded. Supports two-tier approval based on threshold severity.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApprovalThresholdEvaluatorImpl implements ApprovalThresholdEvaluator {

    private final ApprovalThresholdConfigRepository thresholdConfigRepository;

    @Override
    public Optional<ApprovalTier> evaluateRequiredApprovalTier(CycleCountAdjustment adjustment) {
        List<ApprovalThresholdConfig> configs = thresholdConfigRepository.findByActiveTrueOrderByApprovalTierAsc();

        if (configs.isEmpty()) {
            log.warn("No active approval threshold configurations found. Auto-approving adjustment {}",
                    adjustment.getAdjustmentId());
            return Optional.empty();
        }

        // Calculate variance metrics
        int absUnitVariance = Math.abs(adjustment.getQuantityChange());
        BigDecimal absValueVariance = adjustment.getVarianceValue();
        BigDecimal percentVariance = adjustment.getVariancePercentage();

        log.debug("Evaluating adjustment {} - Unit variance: {}, Value variance: ${}, Percent variance: {}%",
                adjustment.getAdjustmentId(), absUnitVariance, absValueVariance, percentVariance);

        // Evaluate tiers from highest to lowest to determine the minimum required tier
        ApprovalTier requiredTier = null;

        for (ApprovalThresholdConfig config : configs) {
            if (exceedsThreshold(absUnitVariance, absValueVariance, percentVariance, config)) {
                requiredTier = config.getApprovalTier();
                log.info(
                        "Adjustment {} requires {} approval - exceeds threshold: units={}/{}, value=${}/{}, percent={}%/{}%",
                        adjustment.getAdjustmentId(),
                        requiredTier,
                        absUnitVariance, config.getUnitVarianceThreshold(),
                        absValueVariance, config.getValueVarianceThreshold(),
                        percentVariance, config.getPercentageVarianceThreshold());
            }
        }

        return Optional.ofNullable(requiredTier);
    }

    /**
     * Checks if variance exceeds ANY threshold in the configuration (OR logic).
     */
    private boolean exceedsThreshold(
            int absUnitVariance,
            BigDecimal absValueVariance,
            BigDecimal percentVariance,
            ApprovalThresholdConfig config) {
        boolean exceedsUnit = absUnitVariance >= config.getUnitVarianceThreshold();
        boolean exceedsValue = absValueVariance.compareTo(config.getValueVarianceThreshold()) >= 0;
        boolean exceedsPercent = percentVariance.compareTo(config.getPercentageVarianceThreshold()) >= 0;

        return exceedsUnit || exceedsValue || exceedsPercent;
    }
}
