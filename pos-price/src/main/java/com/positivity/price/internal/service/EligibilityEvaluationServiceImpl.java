package com.positivity.price.internal.service;

import com.positivity.price.internal.client.AccountDataProvider;
import com.positivity.price.internal.client.AccountContext;
import com.positivity.price.internal.client.VehicleDataProvider;
import com.positivity.price.internal.client.VehicleContext;
import com.positivity.price.internal.dto.AddEligibilityRuleRequest;
import com.positivity.price.internal.entity.PromotionEligibilityRule;
import com.positivity.price.internal.enums.ConditionType;
import com.positivity.price.internal.enums.EligibilityReasonCode;
import com.positivity.price.internal.enums.RuleCombination;
import com.positivity.price.internal.enums.RuleOperator;
import com.positivity.price.internal.exception.EligibilityRuleNotFoundException;
import com.positivity.price.internal.exception.PromotionOfferNotFoundException;
import com.positivity.price.internal.repository.PromotionEligibilityRuleRepository;
import com.positivity.price.internal.repository.PromotionOfferRepository;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.price.service.EligibilityDecision;
import com.positivity.price.service.EligibilityEvaluationService;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementation of promotion eligibility rule management and evaluation service. */
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
    public PromotionEligibilityRule addRule(
            @NonNull UUID promotionId, @NonNull AddEligibilityRuleRequest request) {
        promotionOfferRepository
                .findById(promotionId)
                .orElseThrow(() -> new PromotionOfferNotFoundException(promotionId));

        PromotionEligibilityRule rule = new PromotionEligibilityRule();
        rule.setPromotionId(promotionId);
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
        return promotionEligibilityRuleRepository.findByPromotionId(promotionId);
    }

    @Override
    @Transactional
    public void deleteRule(@NonNull UUID ruleId) {
        promotionEligibilityRuleRepository
                .findById(ruleId)
                .orElseThrow(() -> new EligibilityRuleNotFoundException(ruleId));
        promotionEligibilityRuleRepository.deleteById(ruleId);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public EligibilityDecision evaluateEligibility(
            @NonNull UUID promotionId, @Nullable UUID accountId, @Nullable UUID vehicleId) {
        List<PromotionEligibilityRule> rules = promotionEligibilityRuleRepository.findByPromotionId(promotionId);
        if (rules.isEmpty()) {
            return new EligibilityDecision(true, EligibilityReasonCode.ELIGIBLE);
        }

        RuleCombination combination = rules.get(0).getRuleCombination() != null
                ? rules.get(0).getRuleCombination()
                : RuleCombination.AND;

        if (combination == RuleCombination.OR) {
            EligibilityReasonCode lastFailure = EligibilityReasonCode.EVALUATION_ERROR;
            for (PromotionEligibilityRule rule : rules) {
                EligibilityDecision decision = evaluateSingleRule(rule, accountId, vehicleId);
                if (decision.isEligible()) {
                    return decision;
                }
                lastFailure = decision.reasonCode();
            }
            return new EligibilityDecision(false, lastFailure);
        }

        for (PromotionEligibilityRule rule : rules) {
            EligibilityDecision decision = evaluateSingleRule(rule, accountId, vehicleId);
            if (!decision.isEligible()) {
                return decision;
            }
        }

        return new EligibilityDecision(true, EligibilityReasonCode.ELIGIBLE);
    }

    @NonNull
    private EligibilityDecision evaluateSingleRule(
            @NonNull PromotionEligibilityRule rule, @Nullable UUID accountId, @Nullable UUID vehicleId) {
        ConditionType conditionType = rule.getConditionType();
        RuleOperator operator = rule.getOperator();
        String value = rule.getValue();

        if (conditionType == ConditionType.ACCOUNT_ID_LIST || conditionType == ConditionType.ACCOUNT_FLEET_SIZE) {
            if (accountId == null) {
                return new EligibilityDecision(false, EligibilityReasonCode.MISSING_ACCOUNT_CONTEXT);
            }
            Optional<AccountContext> accountContext = accountDataProvider.getAccountContext(accountId);
            if (accountContext.isEmpty()) {
                return new EligibilityDecision(false, EligibilityReasonCode.MISSING_ACCOUNT_CONTEXT);
            }

            if (conditionType == ConditionType.ACCOUNT_ID_LIST) {
                List<String> accountIds = Arrays.stream(value.split(","))
                        .map(String::trim)
                        .toList();
                boolean inList = accountIds.contains(accountId.toString());
                if (operator == RuleOperator.IN && !inList) {
                    return new EligibilityDecision(false, EligibilityReasonCode.ACCOUNT_NOT_IN_LIST);
                }
                if (operator == RuleOperator.NOT_IN && inList) {
                    return new EligibilityDecision(false, EligibilityReasonCode.ACCOUNT_IN_EXCLUSION_LIST);
                }
            } else {
                int threshold = Integer.parseInt(value.trim());
                if (operator == RuleOperator.GREATER_THAN_OR_EQUAL_TO
                        && accountContext.get().fleetSize() < threshold) {
                    return new EligibilityDecision(false, EligibilityReasonCode.FLEET_SIZE_TOO_SMALL);
                }
            }
        } else if (conditionType == ConditionType.VEHICLE_TAG) {
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
                return new EligibilityDecision(false, EligibilityReasonCode.VEHICLE_TAG_NOT_PRESENT);
            }
        }

        return new EligibilityDecision(true, EligibilityReasonCode.ELIGIBLE);
    }
}
