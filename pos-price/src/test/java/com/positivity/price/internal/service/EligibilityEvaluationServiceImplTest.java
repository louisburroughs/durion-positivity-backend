package com.positivity.price.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.price.internal.client.AccountContext;
import com.positivity.price.internal.client.AccountDataProvider;
import com.positivity.price.internal.client.VehicleContext;
import com.positivity.price.internal.client.VehicleDataProvider;
import com.positivity.price.internal.entity.PromotionEligibilityRule;
import com.positivity.price.internal.enums.ConditionType;
import com.positivity.price.internal.enums.EligibilityReasonCode;
import com.positivity.price.internal.enums.RuleCombination;
import com.positivity.price.internal.enums.RuleOperator;
import com.positivity.price.internal.exception.EligibilityRuleNotFoundException;
import com.positivity.price.internal.repository.PromotionEligibilityRuleRepository;
import com.positivity.price.internal.repository.PromotionOfferRepository;
import com.positivity.price.service.EligibilityDecision;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EligibilityEvaluationServiceImplTest {

        @Mock
        private PromotionEligibilityRuleRepository ruleRepo;

        @Mock
        private AccountDataProvider accountProvider;

        @Mock
        private VehicleDataProvider vehicleProvider;

        @Mock
        private PromotionOfferRepository promotionRepo;

        @Test
        void givenAccountIdListRule_andMatchingAccount_whenEvaluate_thenEligible() {
                UUID promotionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000002");
                PromotionEligibilityRule rule = rule(ConditionType.ACCOUNT_ID_LIST, RuleOperator.IN,
                                accountId.toString());

                when(ruleRepo.findByPromotionId(any())).thenReturn(List.of(rule));
                when(accountProvider.getAccountContext(any()))
                                .thenReturn(Optional.of(new AccountContext(accountId, 5)));

                EligibilityEvaluationServiceImpl service = new EligibilityEvaluationServiceImpl(
                                ruleRepo,
                                promotionRepo,
                                accountProvider,
                                vehicleProvider);

                EligibilityDecision decision = service.evaluateEligibility(promotionId, accountId, null);

                assertThat(decision.isEligible()).isTrue();
                assertThat(decision.reasonCode()).isEqualTo(EligibilityReasonCode.ELIGIBLE);
        }

        @Test
        void givenVehicleTagRule_andTagPresent_whenEvaluate_thenEligible() {
                UUID promotionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID vehicleId = UUID.fromString("00000000-0000-0000-0000-000000000002");
                PromotionEligibilityRule rule = rule(ConditionType.VEHICLE_TAG, RuleOperator.EQUALS, "tractor");

                when(ruleRepo.findByPromotionId(any())).thenReturn(List.of(rule));
                when(vehicleProvider.getVehicleContext(any()))
                                .thenReturn(Optional.of(new VehicleContext(vehicleId, List.of("tractor"))));

                EligibilityEvaluationServiceImpl service = new EligibilityEvaluationServiceImpl(
                                ruleRepo,
                                promotionRepo,
                                accountProvider,
                                vehicleProvider);

                EligibilityDecision decision = service.evaluateEligibility(promotionId, null, vehicleId);

                assertThat(decision.isEligible()).isTrue();
                assertThat(decision.reasonCode()).isEqualTo(EligibilityReasonCode.ELIGIBLE);
        }

        @Test
        void givenFleetSizeRule_andFleetTooSmall_whenEvaluate_thenFleetSizeTooSmall() {
                UUID promotionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000002");
                PromotionEligibilityRule rule = rule(
                                ConditionType.ACCOUNT_FLEET_SIZE,
                                RuleOperator.GREATER_THAN_OR_EQUAL_TO,
                                "20");

                when(ruleRepo.findByPromotionId(any())).thenReturn(List.of(rule));
                when(accountProvider.getAccountContext(any()))
                                .thenReturn(Optional.of(new AccountContext(accountId, 5)));

                EligibilityEvaluationServiceImpl service = new EligibilityEvaluationServiceImpl(
                                ruleRepo,
                                promotionRepo,
                                accountProvider,
                                vehicleProvider);

                EligibilityDecision decision = service.evaluateEligibility(promotionId, accountId, null);

                assertThat(decision.isEligible()).isFalse();
                assertThat(decision.reasonCode()).isEqualTo(EligibilityReasonCode.FLEET_SIZE_TOO_SMALL);
        }

        @Test
        void givenVehicleTagRule_andTagAbsent_whenEvaluate_thenVehicleTagNotPresent() {
                UUID promotionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID vehicleId = UUID.fromString("00000000-0000-0000-0000-000000000002");
                PromotionEligibilityRule rule = rule(ConditionType.VEHICLE_TAG, RuleOperator.EQUALS, "tractor");

                when(ruleRepo.findByPromotionId(any())).thenReturn(List.of(rule));
                when(vehicleProvider.getVehicleContext(any()))
                                .thenReturn(Optional.of(new VehicleContext(vehicleId, List.of("trailer"))));

                EligibilityEvaluationServiceImpl service = new EligibilityEvaluationServiceImpl(
                                ruleRepo,
                                promotionRepo,
                                accountProvider,
                                vehicleProvider);

                EligibilityDecision decision = service.evaluateEligibility(promotionId, null, vehicleId);

                assertThat(decision.isEligible()).isFalse();
                assertThat(decision.reasonCode()).isEqualTo(EligibilityReasonCode.VEHICLE_TAG_NOT_PRESENT);
        }

        /**
         * EE-005: OR combination — first rule fails (account not in list), second rule
         * passes (vehicle tag matches).
         *
         * <p>
         * Issue: #96
         */
        @Test
        void givenOrCombinationRules_andFirstFailsSecondPasses_whenEvaluate_thenEligible() {
                UUID promotionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000002");
                UUID vehicleId = UUID.fromString("00000000-0000-0000-0000-000000000003");

                PromotionEligibilityRule rule1 = rule(ConditionType.ACCOUNT_ID_LIST, RuleOperator.IN, "other-uuid");
                rule1.setRuleCombination(RuleCombination.OR);

                PromotionEligibilityRule rule2 = rule(ConditionType.VEHICLE_TAG, RuleOperator.EQUALS, "fleet");
                rule2.setRuleCombination(RuleCombination.OR);

                when(ruleRepo.findByPromotionId(promotionId)).thenReturn(List.of(rule1, rule2));
                when(accountProvider.getAccountContext(accountId))
                                .thenReturn(Optional.of(new AccountContext(accountId, 5)));
                when(vehicleProvider.getVehicleContext(vehicleId))
                                .thenReturn(Optional.of(new VehicleContext(vehicleId, List.of("fleet"))));

                EligibilityEvaluationServiceImpl service = new EligibilityEvaluationServiceImpl(
                                ruleRepo,
                                promotionRepo,
                                accountProvider,
                                vehicleProvider);

                EligibilityDecision result = service.evaluateEligibility(promotionId, accountId, vehicleId);

                assertThat(result.isEligible()).isTrue();
                assertThat(result.reasonCode()).isEqualTo(EligibilityReasonCode.ELIGIBLE);
        }

        @Test
        void givenNoRules_whenEvaluate_thenEligible() {
                UUID promotionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                when(ruleRepo.findByPromotionId(promotionId)).thenReturn(List.of());

                EligibilityEvaluationServiceImpl service = new EligibilityEvaluationServiceImpl(
                                ruleRepo,
                                promotionRepo,
                                accountProvider,
                                vehicleProvider);

                EligibilityDecision result = service.evaluateEligibility(promotionId, null, null);

                assertThat(result.isEligible()).isTrue();
                assertThat(result.reasonCode()).isEqualTo(EligibilityReasonCode.ELIGIBLE);
        }

        @Test
        void givenAccountIdListRule_andNonMatchingAccount_whenEvaluate_thenNotEligible() {
                UUID promotionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000002");
                UUID otherAccountId = UUID.fromString("00000000-0000-0000-0000-000000000003");
                PromotionEligibilityRule rule = rule(ConditionType.ACCOUNT_ID_LIST, RuleOperator.IN,
                                otherAccountId.toString());

                when(ruleRepo.findByPromotionId(any())).thenReturn(List.of(rule));
                when(accountProvider.getAccountContext(any()))
                                .thenReturn(Optional.of(new AccountContext(accountId, 1)));

                EligibilityEvaluationServiceImpl service = new EligibilityEvaluationServiceImpl(
                                ruleRepo,
                                promotionRepo,
                                accountProvider,
                                vehicleProvider);

                EligibilityDecision decision = service.evaluateEligibility(promotionId, accountId, null);

                assertThat(decision.isEligible()).isFalse();
                assertThat(decision.reasonCode()).isEqualTo(EligibilityReasonCode.ACCOUNT_NOT_IN_LIST);
        }

        @Test
        void givenAccountIdExclusionListRule_andMatchingAccount_whenEvaluate_thenNotEligible() {
                UUID promotionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000002");
                PromotionEligibilityRule rule = rule(ConditionType.ACCOUNT_ID_LIST, RuleOperator.NOT_IN,
                                accountId.toString());

                when(ruleRepo.findByPromotionId(any())).thenReturn(List.of(rule));
                when(accountProvider.getAccountContext(any()))
                                .thenReturn(Optional.of(new AccountContext(accountId, 1)));

                EligibilityEvaluationServiceImpl service = new EligibilityEvaluationServiceImpl(
                                ruleRepo,
                                promotionRepo,
                                accountProvider,
                                vehicleProvider);

                EligibilityDecision decision = service.evaluateEligibility(promotionId, accountId, null);

                assertThat(decision.isEligible()).isFalse();
                assertThat(decision.reasonCode()).isEqualTo(EligibilityReasonCode.ACCOUNT_IN_EXCLUSION_LIST);
        }

        @Test
        void givenAccountIdListRule_withUnsupportedOperator_whenEvaluate_thenEvaluationError() {
                UUID promotionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000002");
                PromotionEligibilityRule rule = rule(ConditionType.ACCOUNT_ID_LIST, RuleOperator.EQUALS,
                                accountId.toString());

                when(ruleRepo.findByPromotionId(any())).thenReturn(List.of(rule));

                EligibilityEvaluationServiceImpl service = new EligibilityEvaluationServiceImpl(
                                ruleRepo,
                                promotionRepo,
                                accountProvider,
                                vehicleProvider);

                EligibilityDecision decision = service.evaluateEligibility(promotionId, accountId, null);

                assertThat(decision.isEligible()).isFalse();
                assertThat(decision.reasonCode()).isEqualTo(EligibilityReasonCode.EVALUATION_ERROR);
        }

        @Test
        void givenFleetSizeRule_andMissingAccountContext_whenEvaluate_thenMissingContext() {
                UUID promotionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                PromotionEligibilityRule rule = rule(ConditionType.ACCOUNT_FLEET_SIZE,
                                RuleOperator.GREATER_THAN_OR_EQUAL_TO,
                                "10");

                when(ruleRepo.findByPromotionId(any())).thenReturn(List.of(rule));

                EligibilityEvaluationServiceImpl service = new EligibilityEvaluationServiceImpl(
                                ruleRepo,
                                promotionRepo,
                                accountProvider,
                                vehicleProvider);

                EligibilityDecision decision = service.evaluateEligibility(promotionId, null, null);

                assertThat(decision.isEligible()).isFalse();
                assertThat(decision.reasonCode()).isEqualTo(EligibilityReasonCode.MISSING_ACCOUNT_CONTEXT);
        }

        @Test
        void givenVehicleTagRule_withUnsupportedOperator_whenEvaluate_thenEvaluationError() {
                UUID promotionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID vehicleId = UUID.fromString("00000000-0000-0000-0000-000000000003");
                PromotionEligibilityRule rule = rule(ConditionType.VEHICLE_TAG, RuleOperator.IN, "tractor");

                when(ruleRepo.findByPromotionId(any())).thenReturn(List.of(rule));

                EligibilityEvaluationServiceImpl service = new EligibilityEvaluationServiceImpl(
                                ruleRepo,
                                promotionRepo,
                                accountProvider,
                                vehicleProvider);

                EligibilityDecision decision = service.evaluateEligibility(promotionId, null, vehicleId);

                assertThat(decision.isEligible()).isFalse();
                assertThat(decision.reasonCode()).isEqualTo(EligibilityReasonCode.EVALUATION_ERROR);
        }

        @Test
        void givenMatchingPromotionAndRule_whenDeleteRule_thenDeletesScopedRule() {
                UUID promotionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID ruleId = UUID.fromString("00000000-0000-0000-0000-000000000002");
                when(ruleRepo.deleteByRuleIdAndPromotionId(ruleId, promotionId)).thenReturn(1L);

                EligibilityEvaluationServiceImpl service = new EligibilityEvaluationServiceImpl(
                                ruleRepo,
                                promotionRepo,
                                accountProvider,
                                vehicleProvider);

                service.deleteRule(promotionId, ruleId);

                verify(ruleRepo).deleteByRuleIdAndPromotionId(ruleId, promotionId);
        }

        @Test
        void givenRuleNotUnderPromotion_whenDeleteRule_thenThrowsNotFound() {
                UUID promotionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID ruleId = UUID.fromString("00000000-0000-0000-0000-000000000003");
                when(ruleRepo.deleteByRuleIdAndPromotionId(ruleId, promotionId)).thenReturn(0L);

                EligibilityEvaluationServiceImpl service = new EligibilityEvaluationServiceImpl(
                                ruleRepo,
                                promotionRepo,
                                accountProvider,
                                vehicleProvider);

                assertThatThrownBy(() -> service.deleteRule(promotionId, ruleId))
                                .isInstanceOf(EligibilityRuleNotFoundException.class)
                                .hasMessageContaining(ruleId.toString());
        }

        private PromotionEligibilityRule rule(ConditionType conditionType, RuleOperator operator, String value) {
                PromotionEligibilityRule rule = new PromotionEligibilityRule();
                rule.setConditionType(conditionType);
                rule.setOperator(operator);
                rule.setValue(value);
                return rule;
        }
}
