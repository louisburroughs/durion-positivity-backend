package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.ApprovalThresholdConfig;
import com.positivity.inventory.internal.entity.CycleCountAdjustment;
import com.positivity.inventory.internal.enums.ApprovalFlowType;
import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.repository.ApprovalThresholdConfigRepository;
import com.positivity.inventory.service.ApprovalThresholdEvaluator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Evaluates cycle count adjustments against approval thresholds.
 *
 * <p>
 * Uses a composite threshold model: approval is required if ANY threshold is
 * exceeded. Supports two-tier approval based on threshold severity.
 *
 * <p>Since odoo-parity D1 (issue #1030) the config table is shared between
 * approval flows ({@link ApprovalFlowType}); cycle-count evaluation reads only
 * {@code CYCLE_COUNT} rows and scrap evaluation only {@code SCRAP} rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApprovalThresholdEvaluatorImpl implements ApprovalThresholdEvaluator {

    private final ApprovalThresholdConfigRepository thresholdConfigRepository;

    @Override
    public Optional<ApprovalTier> evaluateRequiredApprovalTier(CycleCountAdjustment adjustment) {
        List<ApprovalThresholdConfig> configs =
                thresholdConfigRepository.findByFlowTypeAndActiveTrueOrderByApprovalTierAsc(
                        ApprovalFlowType.CYCLE_COUNT);

        if (configs.isEmpty()) {
            log.warn(
                    "No active approval threshold configurations found. Auto-approving adjustment {}",
                    adjustment.getAdjustmentId());
            return Optional.empty();
        }

        // Calculate variance metrics
        BigDecimal absUnitVariance = adjustment.getQuantityChange().abs();
        BigDecimal absValueVariance = adjustment.getVarianceValue();
        BigDecimal percentVariance = adjustment.getVariancePercentage();

        log.debug(
                "Evaluating adjustment {} - Unit variance: {}, Value variance: ${}, Percent variance: {}%",
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
                        absUnitVariance,
                        config.getUnitVarianceThreshold(),
                        absValueVariance,
                        config.getValueVarianceThreshold(),
                        percentVariance,
                        config.getPercentageVarianceThreshold());
            }
        }

        return Optional.ofNullable(requiredTier);
    }

    @Override
    public Optional<ApprovalTier> evaluateScrapApprovalTier(@NonNull BigDecimal scrapValue) {
        List<ApprovalThresholdConfig> configs =
                thresholdConfigRepository.findByFlowTypeAndActiveTrueOrderByApprovalTierAsc(ApprovalFlowType.SCRAP);

        if (configs.isEmpty()) {
            log.warn(
                    "No active SCRAP approval threshold configurations found. Auto-approving scrap value {}",
                    scrapValue);
            return Optional.empty();
        }

        // Scrap thresholds are value-based only (odoo-parity D1): the highest
        // tier whose value threshold is met wins; below all thresholds means
        // auto-approval.
        ApprovalTier requiredTier = null;
        for (ApprovalThresholdConfig config : configs) {
            if (scrapValue.compareTo(config.getValueVarianceThreshold()) >= 0) {
                requiredTier = config.getApprovalTier();
            }
        }
        if (requiredTier != null) {
            log.info("Scrap value {} requires {} approval", scrapValue, requiredTier);
        }
        return Optional.ofNullable(requiredTier);
    }

    @Override
    public Optional<ApprovalTier> evaluateRevaluationApprovalTier(@NonNull BigDecimal absValueDelta) {
        List<ApprovalThresholdConfig> configs =
                thresholdConfigRepository.findByFlowTypeAndActiveTrueOrderByApprovalTierAsc(
                        ApprovalFlowType.REVALUATION);

        if (configs.isEmpty()) {
            log.warn(
                    "No active REVALUATION approval threshold configurations found. Auto-approving revaluation delta {}",
                    absValueDelta);
            return Optional.empty();
        }

        // Revaluation thresholds are value-based only (odoo-parity J4): the highest tier whose value
        // threshold is met wins; below all thresholds means auto-approval.
        ApprovalTier requiredTier = null;
        for (ApprovalThresholdConfig config : configs) {
            if (absValueDelta.compareTo(config.getValueVarianceThreshold()) >= 0) {
                requiredTier = config.getApprovalTier();
            }
        }
        if (requiredTier != null) {
            log.info("Revaluation value delta {} requires {} approval", absValueDelta, requiredTier);
        }
        return Optional.ofNullable(requiredTier);
    }

    /**
     * Checks if variance exceeds ANY threshold in the configuration (OR logic).
     */
    private boolean exceedsThreshold(
            BigDecimal absUnitVariance,
            BigDecimal absValueVariance,
            BigDecimal percentVariance,
            ApprovalThresholdConfig config) {
        boolean exceedsUnit = absUnitVariance.compareTo(BigDecimal.valueOf(config.getUnitVarianceThreshold())) >= 0;
        boolean exceedsValue = absValueVariance.compareTo(config.getValueVarianceThreshold()) >= 0;
        boolean exceedsPercent = percentVariance.compareTo(config.getPercentageVarianceThreshold()) >= 0;

        return exceedsUnit || exceedsValue || exceedsPercent;
    }
}
