package com.positivity.price.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.positivity.price.internal.dto.RestrictionEvaluationItem;
import com.positivity.price.internal.dto.RestrictionEvaluationRequest;
import com.positivity.price.internal.entity.RestrictionRule;
import com.positivity.price.internal.enums.EvaluationContext;
import com.positivity.price.internal.enums.LocationTag;
import com.positivity.price.internal.enums.RestrictionDecision;
import com.positivity.price.internal.enums.ServiceTag;
import com.positivity.price.internal.exception.RestrictionServiceUnavailableException;
import com.positivity.price.internal.repository.RestrictionRuleRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link RestrictionEvaluationServiceImpl} evaluation logic.
 *
 * <p>
 * Covers decision outcomes (ALLOW, BLOCK, ALLOW_WITH_OVERRIDE,
 * RESTRICTION_UNKNOWN),
 * fail-safe semantics on service unavailability by context (BROWSE →
 * RESTRICTION_UNKNOWN,
 * COMMIT/CHECKOUT → exception), and multi-item result cardinality.
 * </p>
 *
 * Issue: #43
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RestrictionEvaluationServiceImpl – evaluation decisions and fail-safe")
class RestrictionEvaluationServiceImplTest {

    @Mock
    private RestrictionRuleRepository repository;

    private RestrictionEvaluationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RestrictionEvaluationServiceImpl(repository, 800L);
    }

    /**
     * When no active rule matches a product, the decision must be ALLOW.
     */
    @Test
    @DisplayName("RES-001: no matching rule → decision ALLOW")
    void givenProductWithNoMatchingRule_whenEvaluate_thenDecisionIsALLOW() {
        var request = evaluationRequest(EvaluationContext.BROWSE, 1);
        var item = request.items().get(0);
        when(repository.findByProductIdAndActiveTrueAndLocationTagAndServiceTag(
                item.productId(), item.locationTag(), item.serviceTag())).thenReturn(List.of());

        var results = service.evaluate(request);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).decision()).isEqualTo(RestrictionDecision.ALLOW);
    }

    /**
     * When a BLOCK rule matches, the decision must be BLOCK.
     */
    @Test
    @DisplayName("RES-002: BLOCK rule matches → decision BLOCK")
    void givenProductMatchingBlockRule_whenEvaluate_thenDecisionIsBLOCK() {
        var request = evaluationRequest(EvaluationContext.BROWSE, 1);
        var item = request.items().get(0);
        when(repository.findByProductIdAndActiveTrueAndLocationTagAndServiceTag(
                item.productId(), item.locationTag(), item.serviceTag())).thenReturn(List.of(rule(false)));

        var results = service.evaluate(request);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).decision()).isEqualTo(RestrictionDecision.BLOCK);
    }

    /**
     * When an overridable rule matches, the decision must be ALLOW_WITH_OVERRIDE.
     */
    @Test
    @DisplayName("RES-003: overridable rule matches → decision ALLOW_WITH_OVERRIDE")
    void givenProductMatchingOverridableRule_whenEvaluate_thenDecisionIsALLOW_WITH_OVERRIDE() {
        var request = evaluationRequest(EvaluationContext.QUOTE, 1);
        var item = request.items().get(0);
        when(repository.findByProductIdAndActiveTrueAndLocationTagAndServiceTag(
                item.productId(), item.locationTag(), item.serviceTag())).thenReturn(List.of(rule(true)));

        var results = service.evaluate(request);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).decision()).isEqualTo(RestrictionDecision.ALLOW_WITH_OVERRIDE);
    }

    /**
     * On BROWSE path when the underlying rule data source is unavailable, the
     * service must
     * degrade gracefully and return RESTRICTION_UNKNOWN rather than propagate an
     * exception.
     */
    @Test
    @DisplayName("RES-004: BROWSE context + service unavailable → RESTRICTION_UNKNOWN (no exception)")
    void givenBrowsePath_andServiceUnavailable_whenEvaluate_thenDecisionIsRESTRICTION_UNKNOWN() {
        var request = evaluationRequest(EvaluationContext.BROWSE, 1);
        var item = request.items().get(0);
        when(repository.findByProductIdAndActiveTrueAndLocationTagAndServiceTag(
                item.productId(), item.locationTag(), item.serviceTag())).thenThrow(new RuntimeException("down"));

        var results = service.evaluate(request);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).decision()).isEqualTo(RestrictionDecision.RESTRICTION_UNKNOWN);
    }

    /**
     * On COMMIT_SALE path when the rule data source is unavailable, the service
     * must
     * throw {@link RestrictionServiceUnavailableException} (no silent degradation
     * on
     * commit-critical paths).
     */
    @Test
    @DisplayName("RES-005: COMMIT_SALE + service unavailable → RestrictionServiceUnavailableException")
    void givenCommitPath_andServiceUnavailable_whenEvaluate_thenThrowsServiceUnavailableException() {
        var request = evaluationRequest(EvaluationContext.COMMIT_SALE, 1);
        var item = request.items().get(0);
        when(repository.findByProductIdAndActiveTrueAndLocationTagAndServiceTag(
                item.productId(), item.locationTag(), item.serviceTag())).thenThrow(new RuntimeException("down"));

        assertThatThrownBy(() -> service.evaluate(request))
                .isInstanceOf(RestrictionServiceUnavailableException.class);
    }

    /**
     * With N items in the request, the result list must contain exactly N entries —
     * one
     * per submitted product.
     */
    @Test
    @DisplayName("RES-006: 3 items → exactly 3 results (1:1 cardinality)")
    void givenMultipleProducts_whenEvaluate_thenReturnsOneResultPerProduct() {
        var request = evaluationRequest(EvaluationContext.BROWSE, 3);
        when(repository.findByProductIdAndActiveTrueAndLocationTagAndServiceTag(any(), any(), any()))
                .thenReturn(List.of());

        var results = service.evaluate(request);

        assertThat(results).hasSize(3);
    }

    /**
     * When evaluation exceeds the 800 ms SLA on a commit-critical context, the
     * service must
     * throw {@link RestrictionServiceUnavailableException}.
     */
    @Test
    @DisplayName("RES-007: evaluation timeout on CHECKOUT → RestrictionServiceUnavailableException")
    void givenEvaluationExceedsTimeout_whenEvaluate_thenThrowsServiceUnavailableException() {
        service = new RestrictionEvaluationServiceImpl(repository, 10L);
        var request = evaluationRequest(EvaluationContext.CHECKOUT, 1);
        var item = request.items().get(0);
        doAnswer(invocation -> {
            new java.util.concurrent.Semaphore(0).tryAcquire(100L, TimeUnit.MILLISECONDS);
            return List.of(rule(false));
        }).when(repository).findByProductIdAndActiveTrueAndLocationTagAndServiceTag(
                item.productId(), item.locationTag(), item.serviceTag());

        assertThatThrownBy(() -> service.evaluate(request))
                .isInstanceOf(RestrictionServiceUnavailableException.class);
    }

    // ---- helpers ----

    private RestrictionEvaluationRequest evaluationRequest(EvaluationContext context, int itemCount) {
        List<RestrictionEvaluationItem> items = IntStream.range(0, itemCount)
                .mapToObj(i -> new RestrictionEvaluationItem(
                        UUID.randomUUID(),
                        LocationTag.RETAIL_STORE,
                        ServiceTag.WORKORDER,
                        context))
                .toList();
        return new RestrictionEvaluationRequest(items);
    }

    private RestrictionRule rule(boolean overrideable) {
        return RestrictionRule.builder()
                .ruleId(UUID.randomUUID())
                .productId(UUID.randomUUID())
                .locationTag(LocationTag.RETAIL_STORE)
                .serviceTag(ServiceTag.WORKORDER)
                .effectiveFrom(LocalDate.now().minusDays(1))
                .active(true)
                .overrideable(overrideable)
                .build();
    }
}
