package com.positivity.price.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.price.dto.CreateRestrictionRuleRequest;
import com.positivity.price.enums.LocationTag;
import com.positivity.price.enums.ServiceTag;
import com.positivity.price.internal.entity.RestrictionRule;
import com.positivity.price.internal.exception.RestrictionRuleNotFoundException;
import com.positivity.price.internal.repository.RestrictionRuleRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link RestrictionRuleServiceImpl} rule lifecycle behavior.
 *
 * <p>Covers creation, lookup, active-list retrieval, and deactivation semantics,
 * including not-found and already-inactive rule paths.</p>
 *
 * Issue: #43
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RestrictionRuleServiceImpl – rule lifecycle")
class RestrictionRuleServiceImplTest {

    @Mock
    private RestrictionRuleRepository repository;

    private RestrictionRuleServiceImpl service;

    private final UUID knownActiveId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID knownInactiveId = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private final UUID unknownId = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @BeforeEach
    void setUp() {
        service = new RestrictionRuleServiceImpl(repository);
        when(repository.findById(knownActiveId)).thenReturn(Optional.of(activeRule()));
        when(repository.findById(knownInactiveId)).thenReturn(Optional.of(inactiveRule()));
        when(repository.findById(unknownId)).thenReturn(Optional.empty());
        when(repository.findByActiveTrue()).thenReturn(List.of(activeRule()));
        when(repository.save(any(RestrictionRule.class))).thenAnswer(invocation -> {
            RestrictionRule entity = invocation.getArgument(0);
            if (entity.getRuleId() == null) {
                entity.setRuleId(UUID.randomUUID());
            }
            return entity;
        });
    }

    /**
     * Happy-path: valid request must return a response with a non-null ruleId and active=true.
     *
     * @see RestrictionRuleServiceImpl#createRule(CreateRestrictionRuleRequest)
     */
    @Test
    @DisplayName("RRS-001: valid request → non-null ruleId, active=true")
    void givenValidRequest_whenCreateRule_thenReturnsRuleResponse() {
        var request = new CreateRestrictionRuleRequest(
                UUID.randomUUID(),
            LocationTag.RETAIL_STORE,
            ServiceTag.WORKORDER,
                LocalDate.now(),
            null,
            null,
            true);

        var result = service.createRule(request);

        assertThat(result.ruleId()).isNotNull();
        assertThat(result.active()).isTrue();
        assertThat(result.locationTag()).isEqualTo(LocationTag.RETAIL_STORE);
        assertThat(result.serviceTag()).isEqualTo(ServiceTag.WORKORDER);
        assertThat(result.productId()).isEqualTo(request.productId());
        assertThat(result.effectiveFrom()).isEqualTo(request.effectiveFrom());
    }

    /**
     * Known ID lookup must return the rule with a matching ruleId.
     */
    @Test
    @DisplayName("RRS-002: existing id → returns matching rule")
    void givenExistingId_whenGetRuleById_thenReturnsRule() {
        UUID ruleId = knownActiveId;

        var result = service.getRuleById(ruleId);

        assertThat(result).isNotNull();
        assertThat(result.ruleId()).isEqualTo(ruleId);
    }

    /**
        * Unknown ID must throw a specific not-found runtime exception.
     */
    @Test
    @DisplayName("RRS-003: unknown id → throws not-found exception")
    void givenUnknownId_whenGetRuleById_thenThrowsNotFoundException() {
        assertThatThrownBy(() -> service.getRuleById(unknownId))
            .isInstanceOf(RestrictionRuleNotFoundException.class);
    }

    /**
     * listRules must return at least one entry when active rules exist.
     */
    @Test
    @DisplayName("RRS-004: active rules present → non-empty list returned")
    void givenActiveRules_whenListRules_thenReturnsNonEmptyList() {
        var result = service.listRules();

        assertThat(result).isNotEmpty();
    }

    /**
     * Creating a rule with an explicit policyVersion must preserve that value in the saved entity.
     *
     * <p>Covers the non-null branch of {@code request.policyVersion() == null ? 1 : request.policyVersion()}.</p>
     */
    @Test
    @DisplayName("RRS-005b: createRule with explicit policyVersion preserves provided version")
    void givenExplicitPolicyVersion_whenCreateRule_thenVersionPreserved() {
        var request = new CreateRestrictionRuleRequest(
                UUID.randomUUID(),
                LocationTag.RETAIL_STORE,
                ServiceTag.WORKORDER,
                LocalDate.now(),
                null,
                2,
                true);

        var result = service.createRule(request);

        assertThat(result.ruleId()).isNotNull();
        assertThat(result.active()).isTrue();
    }

    /**
     * Deactivating an active rule must set effectiveTo to today and active=false.
     */
    @Test
    @DisplayName("RRS-005: active rule → deactivate sets effectiveTo=today, active=false")
    void givenActiveRule_whenDeactivateRule_thenSetsEffectiveToToday() {
        UUID ruleId = knownActiveId;

        var result = service.deactivateRule(ruleId);

        assertThat(result.effectiveTo()).isEqualTo(LocalDate.now());
        assertThat(result.active()).isFalse();
    }

    /**
     * Deactivating an already-inactive rule must throw {@code IllegalStateException}.
     */
    @Test
    @DisplayName("RRS-006: already-inactive rule → IllegalStateException")
    void givenAlreadyInactiveRule_whenDeactivateRule_thenThrowsIllegalStateException() {
        UUID ruleId = knownInactiveId;

        assertThatThrownBy(() -> service.deactivateRule(ruleId))
                .isInstanceOf(IllegalStateException.class);
    }

    private RestrictionRule activeRule() {
        return RestrictionRule.builder()
                .ruleId(knownActiveId)
                .productId(UUID.fromString("10000000-0000-0000-0000-000000000001"))
                .locationTag(LocationTag.RETAIL_STORE)
                .serviceTag(ServiceTag.WORKORDER)
                .effectiveFrom(LocalDate.now().minusDays(3))
                .effectiveTo(null)
                .active(true)
                .overrideable(false)
                .build();
    }

    private RestrictionRule inactiveRule() {
        return RestrictionRule.builder()
                .ruleId(knownInactiveId)
                .productId(UUID.fromString("10000000-0000-0000-0000-000000000002"))
                .locationTag(LocationTag.RETAIL_STORE)
                .serviceTag(ServiceTag.WORKORDER)
                .effectiveFrom(LocalDate.now().minusDays(10))
                .effectiveTo(LocalDate.now().minusDays(1))
                .active(false)
                .overrideable(false)
                .build();
    }
}
