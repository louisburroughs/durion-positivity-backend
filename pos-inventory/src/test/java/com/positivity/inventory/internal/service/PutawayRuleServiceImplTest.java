package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.putaway.PutawayRuleRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayRuleResponse;
import com.positivity.inventory.internal.entity.PutawayRule;
import com.positivity.inventory.internal.enums.PutawayDestinationStrategy;
import com.positivity.inventory.internal.enums.PutawayRuleMatchType;
import com.positivity.inventory.internal.exception.DuplicateEnabledAnyPutawayRuleException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.PutawayRuleRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

/** Putaway rule CRUD, including the single-enabled-ANY-rule invariant (issue #1514). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PutawayRuleServiceImpl")
class PutawayRuleServiceImplTest {

    private static final UUID RULE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a01");
    private static final UUID OTHER_RULE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a02");
    private static final UUID DESTINATION = UUID.fromString("01960004-0001-7000-8000-000000000047");
    private static final UUID TIRES_CATEGORY = UUID.fromString("01960030-0000-7000-8000-000000000001");

    @Mock
    private PutawayRuleRepository putawayRuleRepository;

    private PutawayRuleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PutawayRuleServiceImpl(putawayRuleRepository);
        when(putawayRuleRepository.saveAndFlush(any(PutawayRule.class))).thenAnswer(inv -> {
            PutawayRule saved = inv.getArgument(0);
            if (saved.getRuleId() == null) {
                saved.setRuleId(RULE_ID);
            }
            return saved;
        });
        when(putawayRuleRepository.findByMatchTypeAndIsEnabledTrue(any())).thenReturn(List.of());
    }

    private static PutawayRuleRequest request(PutawayRuleMatchType matchType, String matchValue) {
        PutawayRuleRequest request = new PutawayRuleRequest();
        request.setPriority(10);
        request.setMatchType(matchType);
        request.setMatchValue(matchValue);
        request.setDestinationLocationId(DESTINATION);
        return request;
    }

    private static PutawayRule entity(UUID ruleId, PutawayRuleMatchType matchType, int priority) {
        return PutawayRule.builder()
                .ruleId(ruleId)
                .priority(priority)
                .matchType(matchType)
                .matchValue(matchType == PutawayRuleMatchType.ANY ? null : TIRES_CATEGORY.toString())
                .destinationLocationId(DESTINATION)
                .destinationStrategy(PutawayDestinationStrategy.FIXED)
                .isEnabled(true)
                .build();
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("stores the tier, value, destination and strategy as given")
        void createsARule() {
            PutawayRuleResponse response =
                    service.createRule(request(PutawayRuleMatchType.CATEGORY, TIRES_CATEGORY.toString()));

            assertThat(response.getMatchType()).isEqualTo("CATEGORY");
            assertThat(response.getMatchValue()).isEqualTo(TIRES_CATEGORY.toString());
            assertThat(response.getDestinationLocationId()).isEqualTo(DESTINATION.toString());
        }

        @Test
        @DisplayName("defaults the strategy to FIXED and the rule to enabled")
        void appliesDefaults() {
            PutawayRuleResponse response = service.createRule(request(PutawayRuleMatchType.ANY, null));

            assertThat(response.getDestinationStrategy()).isEqualTo("FIXED");
            assertThat(response.getIsEnabled()).isTrue();
        }

        @Test
        @DisplayName("stores a blank match value as null so 'no value' has one representation")
        void normalisesBlankMatchValueToNull() {
            PutawayRuleResponse response = service.createRule(request(PutawayRuleMatchType.ANY, "   "));

            assertThat(response.getMatchValue()).isNull();
        }

        @Test
        @DisplayName("trims a padded match value")
        void trimsMatchValue() {
            PutawayRuleResponse response =
                    service.createRule(request(PutawayRuleMatchType.CATEGORY, "  " + TIRES_CATEGORY + " "));

            assertThat(response.getMatchValue()).isEqualTo(TIRES_CATEGORY.toString());
        }

        @Test
        @DisplayName("#1514 - refuses a second enabled ANY rule, since it could never fire")
        void refusesASecondEnabledAnyRule() {
            when(putawayRuleRepository.findByMatchTypeAndIsEnabledTrue(PutawayRuleMatchType.ANY))
                    .thenReturn(List.of(entity(OTHER_RULE_ID, PutawayRuleMatchType.ANY, 1)));
            PutawayRuleRequest request = request(PutawayRuleMatchType.ANY, null);

            assertThatThrownBy(() -> service.createRule(request))
                    .isInstanceOf(DuplicateEnabledAnyPutawayRuleException.class)
                    .hasMessageContaining(OTHER_RULE_ID.toString());

            verify(putawayRuleRepository, never()).saveAndFlush(any(PutawayRule.class));
        }

        @Test
        @DisplayName("#1514 - a concurrent loser gets the same 409, not a 500")
        void reportsTheConstraintViolationAsTheSameConflict() {
            // The pre-flight read finds nothing, so both racers proceed; the unique constraint on
            // enabled_any_guard decides. The loser must see the ordinary conflict rather than an
            // unhandled integrity error, since from its side nothing unusual happened.
            when(putawayRuleRepository.saveAndFlush(any(PutawayRule.class)))
                    .thenThrow(new DataIntegrityViolationException(
                            "could not execute statement; constraint [putaway_rule_single_enabled_any]"));
            PutawayRuleRequest request = request(PutawayRuleMatchType.ANY, null);

            assertThatThrownBy(() -> service.createRule(request))
                    .isInstanceOf(DuplicateEnabledAnyPutawayRuleException.class)
                    .hasMessageContaining("only one may be enabled");
        }

        @Test
        @DisplayName("an unrelated integrity violation keeps its own error rather than reading as a rule conflict")
        void doesNotSwallowUnrelatedIntegrityViolations() {
            when(putawayRuleRepository.saveAndFlush(any(PutawayRule.class)))
                    .thenThrow(new DataIntegrityViolationException("null value in column \"priority\""));
            PutawayRuleRequest request = request(PutawayRuleMatchType.ANY, null);

            assertThatThrownBy(() -> service.createRule(request))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .isNotInstanceOf(DuplicateEnabledAnyPutawayRuleException.class);
        }

        @Test
        @DisplayName("#1514 - allows a second DISABLED ANY rule, which is unreachable anyway")
        void allowsADisabledSecondAnyRule() {
            when(putawayRuleRepository.findByMatchTypeAndIsEnabledTrue(PutawayRuleMatchType.ANY))
                    .thenReturn(List.of(entity(OTHER_RULE_ID, PutawayRuleMatchType.ANY, 1)));
            PutawayRuleRequest request = request(PutawayRuleMatchType.ANY, null);
            request.setIsEnabled(false);

            assertThat(service.createRule(request).getIsEnabled()).isFalse();
        }

        @Test
        @DisplayName("an existing enabled ANY rule does not block a category rule")
        void anyRuleDoesNotBlockOtherTiers() {
            when(putawayRuleRepository.findByMatchTypeAndIsEnabledTrue(PutawayRuleMatchType.ANY))
                    .thenReturn(List.of(entity(OTHER_RULE_ID, PutawayRuleMatchType.ANY, 1)));

            assertThat(service.createRule(request(PutawayRuleMatchType.CATEGORY, TIRES_CATEGORY.toString()))
                            .getMatchType())
                    .isEqualTo("CATEGORY");
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @BeforeEach
        void existingRule() {
            when(putawayRuleRepository.findById(RULE_ID))
                    .thenReturn(Optional.of(entity(RULE_ID, PutawayRuleMatchType.CATEGORY, 10)));
        }

        @Test
        @DisplayName("replaces the rule's tier and destination")
        void replacesTheRule() {
            PutawayRuleRequest request = request(PutawayRuleMatchType.ANY, null);
            request.setPriority(99);

            PutawayRuleResponse response = service.updateRule(RULE_ID.toString(), request);

            assertThat(response.getMatchType()).isEqualTo("ANY");
            assertThat(response.getPriority()).isEqualTo(99);
        }

        @Test
        @DisplayName("#1514 - an ANY rule does not conflict with itself")
        void ruleDoesNotConflictWithItself() {
            when(putawayRuleRepository.findById(RULE_ID))
                    .thenReturn(Optional.of(entity(RULE_ID, PutawayRuleMatchType.ANY, 10)));
            when(putawayRuleRepository.findByMatchTypeAndIsEnabledTrue(PutawayRuleMatchType.ANY))
                    .thenReturn(List.of(entity(RULE_ID, PutawayRuleMatchType.ANY, 10)));

            assertThat(service.updateRule(RULE_ID.toString(), request(PutawayRuleMatchType.ANY, null))
                            .getMatchType())
                    .isEqualTo("ANY");
        }

        @Test
        @DisplayName("#1514 - refuses promoting a rule to ANY while another enabled ANY rule exists")
        void refusesPromotingToASecondEnabledAnyRule() {
            when(putawayRuleRepository.findByMatchTypeAndIsEnabledTrue(PutawayRuleMatchType.ANY))
                    .thenReturn(List.of(entity(OTHER_RULE_ID, PutawayRuleMatchType.ANY, 1)));
            PutawayRuleRequest request = request(PutawayRuleMatchType.ANY, null);
            String ruleId = RULE_ID.toString();

            assertThatThrownBy(() -> service.updateRule(ruleId, request))
                    .isInstanceOf(DuplicateEnabledAnyPutawayRuleException.class);
        }

        @Test
        @DisplayName("#1514 - omitting isEnabled on a PUT keeps a disabled rule disabled")
        void omittedIsEnabledKeepsTheStoredState() {
            PutawayRule disabled = entity(RULE_ID, PutawayRuleMatchType.CATEGORY, 10);
            disabled.setEnabled(false);
            when(putawayRuleRepository.findById(RULE_ID)).thenReturn(Optional.of(disabled));
            PutawayRuleRequest request = request(PutawayRuleMatchType.CATEGORY, TIRES_CATEGORY.toString());
            request.setPriority(5);
            request.setIsEnabled(null);

            assertThat(service.updateRule(RULE_ID.toString(), request).getIsEnabled())
                    .isFalse();
        }

        @Test
        @DisplayName("an explicit isEnabled on a PUT is honoured")
        void explicitIsEnabledIsHonoured() {
            PutawayRule disabled = entity(RULE_ID, PutawayRuleMatchType.CATEGORY, 10);
            disabled.setEnabled(false);
            when(putawayRuleRepository.findById(RULE_ID)).thenReturn(Optional.of(disabled));
            PutawayRuleRequest request = request(PutawayRuleMatchType.CATEGORY, TIRES_CATEGORY.toString());
            request.setIsEnabled(true);

            assertThat(service.updateRule(RULE_ID.toString(), request).getIsEnabled())
                    .isTrue();
        }

        @Test
        @DisplayName("an unknown rule id is a 404, not a silent create")
        void unknownRuleIdIsNotFound() {
            when(putawayRuleRepository.findById(OTHER_RULE_ID)).thenReturn(Optional.empty());
            PutawayRuleRequest request = request(PutawayRuleMatchType.ANY, null);
            String ruleId = OTHER_RULE_ID.toString();

            assertThatThrownBy(() -> service.updateRule(ruleId, request)).isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("a malformed rule id is rejected as a bad request")
        void malformedRuleIdIsRejected() {
            PutawayRuleRequest request = request(PutawayRuleMatchType.ANY, null);

            assertThatThrownBy(() -> service.updateRule("not-a-uuid", request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ruleId must be a valid UUID");
        }
    }

    @Nested
    @DisplayName("read and delete")
    class ReadAndDelete {

        @Test
        @DisplayName("#1514 - listing is in resolution order: tier precedence first, then priority")
        void listsInResolutionOrder() {
            when(putawayRuleRepository.findAll())
                    .thenReturn(List.of(
                            entity(UUID.randomUUID(), PutawayRuleMatchType.ANY, 1),
                            entity(UUID.randomUUID(), PutawayRuleMatchType.CATEGORY, 20),
                            entity(UUID.randomUUID(), PutawayRuleMatchType.SKU, 50),
                            entity(UUID.randomUUID(), PutawayRuleMatchType.CATEGORY, 10),
                            entity(UUID.randomUUID(), PutawayRuleMatchType.SUBCATEGORY, 30)));

            assertThat(service.listRules())
                    .extracting(PutawayRuleResponse::getMatchType)
                    .containsExactly("SKU", "SUBCATEGORY", "CATEGORY", "CATEGORY", "ANY");
            assertThat(service.listRules())
                    .extracting(PutawayRuleResponse::getPriority)
                    .containsExactly(50, 30, 10, 20, 1);
        }

        @Test
        @DisplayName("reads one rule by id")
        void readsOneRule() {
            when(putawayRuleRepository.findById(RULE_ID))
                    .thenReturn(Optional.of(entity(RULE_ID, PutawayRuleMatchType.CATEGORY, 10)));

            assertThat(service.getRule(RULE_ID.toString()).getRuleId()).isEqualTo(RULE_ID.toString());
        }

        @Test
        @DisplayName("deleting an unknown rule is a 404 rather than a silent success")
        void deletingAnUnknownRuleIsNotFound() {
            when(putawayRuleRepository.findById(RULE_ID)).thenReturn(Optional.empty());
            String ruleId = RULE_ID.toString();

            assertThatThrownBy(() -> service.deleteRule(ruleId)).isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("deletes an existing rule")
        void deletesARule() {
            PutawayRule rule = entity(RULE_ID, PutawayRuleMatchType.CATEGORY, 10);
            when(putawayRuleRepository.findById(RULE_ID)).thenReturn(Optional.of(rule));

            service.deleteRule(RULE_ID.toString());

            verify(putawayRuleRepository).delete(rule);
        }
    }
}
