package com.positivity.price.internal.service;

import com.positivity.price.internal.client.AccountContext;
import com.positivity.price.internal.client.AccountDataProvider;
import com.positivity.price.internal.client.VehicleContext;
import com.positivity.price.internal.client.VehicleDataProvider;
import com.positivity.price.internal.dto.AddEligibilityRuleRequest;
import com.positivity.price.internal.entity.PromotionEligibilityRule;
import com.positivity.price.internal.entity.PromotionOffer;
import com.positivity.price.internal.enums.ConditionType;
import com.positivity.price.internal.enums.EligibilityReasonCode;
import com.positivity.price.internal.enums.RuleCombination;
import com.positivity.price.internal.enums.RuleOperator;
import com.positivity.price.internal.exception.EligibilityRuleNotFoundException;
import com.positivity.price.internal.exception.PromotionOfferNotFoundException;
import com.positivity.price.internal.repository.PromotionEligibilityRuleRepository;
import com.positivity.price.internal.repository.PromotionOfferRepository;
import com.positivity.price.service.EligibilityDecision;
import com.positivity.price.service.EligibilityEvaluationService;
import com.positivity.security.common.SecurityContextHelper;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of promotion eligibility rule management and evaluation
 * service.
 */
@Service
public class EligibilityEvaluationServiceImpl implements EligibilityEvaluationService {

    private final PromotionEligibilityRuleRepository promotionEligibilityRuleRepository;
    private final PromotionOfferRepository promotionOfferRepository;
    private final AccountDataProvider accountDataProvider;
    private final VehicleDataProvider vehicleDataProvider;

    public EligibilityEvaluationServiceImpl(
            PromotionEligibilityRuleRepository promotionEligibilityRuleRepository,
            PromotionOfferRepository promotionOfferRepository,
            AccountDataProvider accountDataProvider,
            VehicleDataProvider vehicleDataProvider) {
        this.promotionEligibilityRuleRepository = promotionEligibilityRuleRepository;
        this.promotionOfferRepository = promotionOfferRepository;
        this.accountDataProvider = accountDataProvider;
        this.vehicleDataProvider = vehicleDataProvider;
    }

    @Override
    @NonNull
    @Transactional
    public PromotionEligibilityRule addRule(@NonNull UUID promotionId, @NonNull AddEligibilityRuleRequest request) {
        PromotionOffer promotion = promotionOfferRepository
                .findById(promotionId)
                .orElseThrow(() -> new PromotionOfferNotFoundException(promotionId));

        PromotionEligibilityRule rule = new PromotionEligibilityRule();
        rule.setPromotion(promotion);
        rule.setConditionType(request.getConditionType());
        rule.setOperator(request.getOperator());
        rule.setValue(request.getValue());
        rule.setRuleCombination(
                request.getRuleCombination() != null ? request.getRuleCombination() : RuleCombination.AND);
        rule.setCreatedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
        return promotionEligibilityRuleRepository.save(rule);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<PromotionEligibilityRule> getRules(@NonNull UUID promotionId) {
        return promotionEligibilityRuleRepository.findByPromotion_PromotionOfferId(promotionId);
    }

    @Override
    @Transactional
    public void deleteRule(@NonNull UUID promotionId, @NonNull UUID ruleId) {
        long deleted =
                promotionEligibilityRuleRepository.deleteByRuleIdAndPromotion_PromotionOfferId(ruleId, promotionId);
        if (deleted == 0) {
            throw new EligibilityRuleNotFoundException(ruleId);
        }
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public EligibilityDecision evaluateEligibility(
            @NonNull UUID promotionId,
            @Nullable UUID accountId,
            @Nullable UUID vehicleId,
            @Nullable String audienceType,
            @Nullable String campaignCode) {
        List<PromotionEligibilityRule> rules =
                promotionEligibilityRuleRepository.findByPromotion_PromotionOfferId(promotionId);
        if (rules.isEmpty()) {
            return new EligibilityDecision(true, EligibilityReasonCode.ELIGIBLE);
        }

        RuleCombination combination =
                rules.get(0).getRuleCombination() != null ? rules.get(0).getRuleCombination() : RuleCombination.AND;

        if (combination == RuleCombination.OR) {
            EligibilityReasonCode lastFailure = EligibilityReasonCode.EVALUATION_ERROR;
            for (PromotionEligibilityRule rule : rules) {
                EligibilityDecision decision =
                        evaluateSingleRule(rule, accountId, vehicleId, audienceType, campaignCode);
                if (decision.isEligible()) {
                    return decision;
                }
                lastFailure = decision.reasonCode();
            }
            return new EligibilityDecision(false, lastFailure);
        }

        for (PromotionEligibilityRule rule : rules) {
            EligibilityDecision decision = evaluateSingleRule(rule, accountId, vehicleId, audienceType, campaignCode);
            if (!decision.isEligible()) {
                return decision;
            }
        }

        return new EligibilityDecision(true, EligibilityReasonCode.ELIGIBLE);
    }

    /**
     * Dispatches one rule to the handler for its condition type.
     *
     * <p>The switch is exhaustive over {@link ConditionType} rather than an {@code if/else} chain
     * with a fall-through. For every condition type that exists today the outcome is identical, but
     * adding a new one now fails to compile instead of silently returning ELIGIBLE — which is the
     * safe direction for a rule engine whose job is to withhold discounts.
     */
    @NonNull
    private EligibilityDecision evaluateSingleRule(
            @NonNull PromotionEligibilityRule rule,
            @Nullable UUID accountId,
            @Nullable UUID vehicleId,
            @Nullable String audienceType,
            @Nullable String campaignCode) {
        ConditionType conditionType = rule.getConditionType();
        RuleOperator operator = rule.getOperator();
        String value = rule.getValue();

        if (!isOperatorSupported(conditionType, operator)) {
            return new EligibilityDecision(false, EligibilityReasonCode.EVALUATION_ERROR);
        }

        return switch (conditionType) {
            case ACCOUNT_ID_LIST, ACCOUNT_FLEET_SIZE -> evaluateAccountRule(conditionType, operator, value, accountId);
            case VEHICLE_TAG -> evaluateVehicleTagRule(operator, value, vehicleId);
            case AUDIENCE_TYPE -> evaluateAudienceTypeRule(operator, value, audienceType);
            case CAMPAIGN_CODE -> evaluateCampaignCodeRule(operator, value, campaignCode);
        };
    }

    /** Both account-scoped condition types need the same context lookup before they diverge. */
    @NonNull
    private EligibilityDecision evaluateAccountRule(
            @NonNull ConditionType conditionType,
            @NonNull RuleOperator operator,
            @NonNull String value,
            @Nullable UUID accountId) {
        if (accountId == null) {
            return new EligibilityDecision(false, EligibilityReasonCode.MISSING_ACCOUNT_CONTEXT);
        }
        Optional<AccountContext> accountContext = accountDataProvider.getAccountContext(accountId);
        if (accountContext.isEmpty()) {
            return new EligibilityDecision(false, EligibilityReasonCode.MISSING_ACCOUNT_CONTEXT);
        }

        return conditionType == ConditionType.ACCOUNT_ID_LIST
                ? evaluateAccountIdList(operator, value, accountId)
                : evaluateFleetSize(operator, value, accountContext.get());
    }

    @NonNull
    private EligibilityDecision evaluateAccountIdList(
            @NonNull RuleOperator operator, @NonNull String value, @NonNull UUID accountId) {
        List<String> accountIds =
                Arrays.stream(value.split(",")).map(String::trim).toList();
        boolean inList = accountIds.contains(accountId.toString());
        if (operator == RuleOperator.IN && !inList) {
            return new EligibilityDecision(false, EligibilityReasonCode.ACCOUNT_NOT_IN_LIST);
        }
        if (operator == RuleOperator.NOT_IN && inList) {
            return new EligibilityDecision(false, EligibilityReasonCode.ACCOUNT_IN_EXCLUSION_LIST);
        }
        return new EligibilityDecision(true, EligibilityReasonCode.ELIGIBLE);
    }

    @NonNull
    private EligibilityDecision evaluateFleetSize(
            @NonNull RuleOperator operator, @NonNull String value, @NonNull AccountContext accountContext) {
        int threshold;
        try {
            threshold = Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return new EligibilityDecision(false, EligibilityReasonCode.EVALUATION_ERROR);
        }
        if (operator == RuleOperator.GREATER_THAN_OR_EQUAL_TO && accountContext.fleetSize() < threshold) {
            return new EligibilityDecision(false, EligibilityReasonCode.FLEET_SIZE_TOO_SMALL);
        }
        return new EligibilityDecision(true, EligibilityReasonCode.ELIGIBLE);
    }

    @NonNull
    private EligibilityDecision evaluateVehicleTagRule(
            @NonNull RuleOperator operator, @NonNull String value, @Nullable UUID vehicleId) {
        if (vehicleId == null) {
            return new EligibilityDecision(false, EligibilityReasonCode.MISSING_VEHICLE_CONTEXT);
        }
        Optional<VehicleContext> vehicleContext = vehicleDataProvider.getVehicleContext(vehicleId);
        if (vehicleContext.isEmpty()) {
            return new EligibilityDecision(false, EligibilityReasonCode.MISSING_VEHICLE_CONTEXT);
        }

        boolean tagPresent = vehicleContext.get().tags().contains(value);
        if (operator == RuleOperator.EQUALS && !tagPresent) {
            return new EligibilityDecision(false, EligibilityReasonCode.VEHICLE_TAG_NOT_PRESENT);
        }
        if (operator == RuleOperator.NOT_IN && tagPresent) {
            return new EligibilityDecision(false, EligibilityReasonCode.VEHICLE_TAG_EXCLUDED);
        }
        return new EligibilityDecision(true, EligibilityReasonCode.ELIGIBLE);
    }

    @NonNull
    private EligibilityDecision evaluateAudienceTypeRule(
            @NonNull RuleOperator operator, @NonNull String value, @Nullable String audienceType) {
        if (audienceType == null || audienceType.isBlank()) {
            return new EligibilityDecision(false, EligibilityReasonCode.MISSING_AUDIENCE_CONTEXT);
        }
        // Audience types are enum-like names (COMMERCIAL, INDIVIDUAL); compare
        // case-insensitively so client casing does not affect the outcome.
        boolean matches = value.trim().equalsIgnoreCase(audienceType.trim());
        if (operator == RuleOperator.EQUALS && !matches) {
            return new EligibilityDecision(false, EligibilityReasonCode.AUDIENCE_TYPE_NOT_MATCHED);
        }
        if (operator == RuleOperator.NOT_IN && matches) {
            return new EligibilityDecision(false, EligibilityReasonCode.AUDIENCE_TYPE_EXCLUDED);
        }
        return new EligibilityDecision(true, EligibilityReasonCode.ELIGIBLE);
    }

    @NonNull
    private EligibilityDecision evaluateCampaignCodeRule(
            @NonNull RuleOperator operator, @NonNull String value, @Nullable String campaignCode) {
        if (campaignCode == null || campaignCode.isBlank()) {
            return new EligibilityDecision(false, EligibilityReasonCode.MISSING_CAMPAIGN_CONTEXT);
        }
        List<String> campaignCodes =
                Arrays.stream(value.split(",")).map(String::trim).toList();
        boolean inList = campaignCodes.contains(campaignCode.trim());
        if ((operator == RuleOperator.EQUALS || operator == RuleOperator.IN) && !inList) {
            return new EligibilityDecision(false, EligibilityReasonCode.CAMPAIGN_CODE_NOT_MATCHED);
        }
        if (operator == RuleOperator.NOT_IN && inList) {
            return new EligibilityDecision(false, EligibilityReasonCode.CAMPAIGN_CODE_EXCLUDED);
        }
        return new EligibilityDecision(true, EligibilityReasonCode.ELIGIBLE);
    }

    private boolean isOperatorSupported(@NonNull ConditionType conditionType, @NonNull RuleOperator operator) {
        return switch (conditionType) {
            case ACCOUNT_ID_LIST -> operator == RuleOperator.IN || operator == RuleOperator.NOT_IN;
            case ACCOUNT_FLEET_SIZE -> operator == RuleOperator.GREATER_THAN_OR_EQUAL_TO;
            case VEHICLE_TAG -> operator == RuleOperator.EQUALS || operator == RuleOperator.NOT_IN;
            case AUDIENCE_TYPE -> operator == RuleOperator.EQUALS || operator == RuleOperator.NOT_IN;
            case CAMPAIGN_CODE ->
                operator == RuleOperator.EQUALS || operator == RuleOperator.IN || operator == RuleOperator.NOT_IN;
        };
    }
}
