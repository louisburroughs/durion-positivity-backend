package com.positivity.catalog.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.catalog.PostgresSliceTestBase;
import com.positivity.catalog.internal.entity.PriceBookEntity;
import com.positivity.catalog.internal.entity.PriceBookRuleConditionType;
import com.positivity.catalog.internal.entity.PriceBookRuleEntity;
import com.positivity.catalog.internal.entity.PriceBookRuleStatus;
import com.positivity.catalog.internal.entity.PriceBookRuleTargetType;
import com.positivity.catalog.internal.entity.PriceBookScope;
import com.positivity.catalog.internal.entity.PriceBookStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The overlap check a price-book rule is admitted by, against the real PostgreSQL baseline.
 *
 * <h2>What this defends</h2>
 *
 * The check was written as one JPQL string of {@code (:param IS NULL OR …)} clauses, which
 * PostgreSQL rejects at parse time with {@code could not determine data type of parameter $10} —
 * the {@code windowStart} placeholder — so creating or updating a rule was a 500 on every call
 * while the H2-backed tests passed (issue #1891). Running this against PostgreSQL is the whole
 * point: on H2 it would pass against the defect too.
 *
 * <p>Beyond that, the two clause shapes the string form conflated are asserted apart: {@code
 * targetId} and {@code conditionValue} are null-safe equality — an untargeted rule conflicts with
 * another untargeted rule — while {@code windowStart} and {@code excludeRuleId} are optional
 * filters that switch off when absent.
 */
@DisplayName("Price-book rule conflict search on PostgreSQL (#1891)")
class PriceBookRuleConflictSearchTest extends PostgresSliceTestBase {

    private static final Instant JAN = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant FEB = Instant.parse("2030-02-01T00:00:00Z");
    private static final Instant MAR = Instant.parse("2030-03-01T00:00:00Z");
    private static final Instant APR = Instant.parse("2030-04-01T00:00:00Z");
    private static final UUID TARGET = UUID.fromString("01900000-0000-7000-8000-0000000000b1");
    private static final UUID OTHER_TARGET = UUID.fromString("01900000-0000-7000-8000-0000000000b2");

    @Autowired
    private PriceBookRepository priceBooks;

    @Autowired
    private PriceBookRuleRepository rules;

    private PriceBookEntity book;

    @BeforeEach
    void createBook() {
        PriceBookEntity entity = new PriceBookEntity();
        entity.setName("conflict-search-" + UUID.randomUUID());
        entity.setScope(PriceBookScope.COMPANY_DEFAULT);
        entity.setStatus(PriceBookStatus.ACTIVE);
        entity.setDefault(false);
        book = priceBooks.saveAndFlush(entity);
    }

    private PriceBookRuleEntity rule(
            PriceBookRuleTargetType targetType,
            UUID targetId,
            String conditionValue,
            Instant startAt,
            Instant endAt,
            PriceBookRuleStatus status) {
        PriceBookRuleEntity entity = new PriceBookRuleEntity();
        entity.setPriceBook(book);
        entity.setTargetType(targetType);
        entity.setTargetId(targetId);
        entity.setConditionType(
                conditionValue == null ? PriceBookRuleConditionType.NONE : PriceBookRuleConditionType.CUSTOMER_TIER);
        entity.setConditionValue(conditionValue);
        entity.setPricingLogic("{\"type\":\"MARKUP\",\"value\":10}");
        entity.setPriority(0);
        entity.setEffectiveStartAt(startAt);
        entity.setEffectiveEndAt(endAt);
        entity.setStatus(status);
        entity.setCreatedByUserId(UUID.fromString("01900000-0000-7000-8000-0000000000c1"));
        return rules.saveAndFlush(entity);
    }

    @Test
    void findsTheOverlappingRuleAndLeavesTheAdjacentOneAlone() {
        PriceBookRuleEntity january =
                rule(PriceBookRuleTargetType.SKU, TARGET, null, JAN, FEB, PriceBookRuleStatus.ACTIVE);
        rule(PriceBookRuleTargetType.SKU, TARGET, null, MAR, APR, PriceBookRuleStatus.ACTIVE);

        assertThat(rules.findConflicts(
                        book.getPriceBookId(),
                        PriceBookRuleTargetType.SKU,
                        TARGET,
                        PriceBookRuleConditionType.NONE,
                        null,
                        JAN,
                        FEB,
                        null))
                .as("the rule covering the same window conflicts")
                .extracting(PriceBookRuleEntity::getRuleId)
                .containsExactly(january.getRuleId());
    }

    @Test
    void ignoresAnotherTargetAnInactiveRuleAndADifferentConditionValue() {
        rule(PriceBookRuleTargetType.SKU, OTHER_TARGET, null, JAN, FEB, PriceBookRuleStatus.ACTIVE);
        rule(PriceBookRuleTargetType.SKU, TARGET, null, JAN, FEB, PriceBookRuleStatus.INACTIVE);
        rule(PriceBookRuleTargetType.SKU, TARGET, "tier-gold", JAN, FEB, PriceBookRuleStatus.ACTIVE);

        assertThat(rules.findConflicts(
                        book.getPriceBookId(),
                        PriceBookRuleTargetType.SKU,
                        TARGET,
                        PriceBookRuleConditionType.NONE,
                        null,
                        JAN,
                        FEB,
                        null))
                .as("a different target, a retired rule and a different condition value are all not conflicts")
                .isEmpty();
    }

    @Test
    void aRuleThatIsNeitherActiveNorInactiveDoesNotBlock() {
        // PriceBookRuleStatus has a third constant, NOT_APPLICABLE_MISSING_BASE. No production path
        // can produce it — PriceBookServiceImpl writes only ACTIVE on create and INACTIVE on
        // deactivate, and PriceBookRuleCreateRequestDto carries no status field — so this fixture
        // sets it on the entity directly, which is the only way such a row can exist at all. It is
        // pinned rather than left to chance because the constant IS reachable from outside the
        // application: the baseline's price_book_rule_status_check admits it and the response schema
        // publishes it, so a data fix or an import could put one in the table.
        //
        // The decision this records: a rule whose base is missing is not in force, so it does not
        // block a create or an update. Under the previous `status <> INACTIVE` filter it would have.
        rule(PriceBookRuleTargetType.SKU, TARGET, null, JAN, FEB, PriceBookRuleStatus.NOT_APPLICABLE_MISSING_BASE);

        assertThat(rules.findConflicts(
                        book.getPriceBookId(),
                        PriceBookRuleTargetType.SKU,
                        TARGET,
                        PriceBookRuleConditionType.NONE,
                        null,
                        JAN,
                        FEB,
                        null))
                .as("a rule that is not in force must not block an overlapping one")
                .isEmpty();
    }

    @Test
    void treatsAnAbsentTargetAndConditionValueAsAValueToMatch() {
        PriceBookRuleEntity untargeted =
                rule(PriceBookRuleTargetType.GLOBAL, null, null, JAN, FEB, PriceBookRuleStatus.ACTIVE);
        rule(PriceBookRuleTargetType.GLOBAL, TARGET, null, JAN, FEB, PriceBookRuleStatus.ACTIVE);

        assertThat(rules.findConflicts(
                        book.getPriceBookId(),
                        PriceBookRuleTargetType.GLOBAL,
                        null,
                        PriceBookRuleConditionType.NONE,
                        null,
                        JAN,
                        FEB,
                        null))
                .as("two rules that both target nothing do conflict; a targeted one does not")
                .extracting(PriceBookRuleEntity::getRuleId)
                .containsExactly(untargeted.getRuleId());
    }

    @Test
    void switchesOffTheLowerBoundAndTheSelfExclusionWhenTheyAreAbsent() {
        PriceBookRuleEntity expired =
                rule(PriceBookRuleTargetType.SKU, TARGET, null, JAN, FEB, PriceBookRuleStatus.ACTIVE);

        assertThat(rules.findConflicts(
                        book.getPriceBookId(),
                        PriceBookRuleTargetType.SKU,
                        TARGET,
                        PriceBookRuleConditionType.NONE,
                        null,
                        null,
                        APR,
                        null))
                .as("with no lower bound every rule starting before the upper bound is in range")
                .extracting(PriceBookRuleEntity::getRuleId)
                .containsExactly(expired.getRuleId());

        assertThat(rules.findConflicts(
                        book.getPriceBookId(),
                        PriceBookRuleTargetType.SKU,
                        TARGET,
                        PriceBookRuleConditionType.NONE,
                        null,
                        null,
                        APR,
                        expired.getRuleId()))
                .as("a rule being updated does not conflict with itself")
                .isEmpty();
    }

    @Test
    void treatsAnOpenEndedRuleAsOverlappingEveryLaterWindow() {
        PriceBookRuleEntity openEnded =
                rule(PriceBookRuleTargetType.SKU, TARGET, null, JAN, null, PriceBookRuleStatus.ACTIVE);

        assertThat(rules.findConflicts(
                        book.getPriceBookId(),
                        PriceBookRuleTargetType.SKU,
                        TARGET,
                        PriceBookRuleConditionType.NONE,
                        null,
                        MAR,
                        APR,
                        null))
                .as("a rule that never ends is still in force in a window two months later")
                .extracting(PriceBookRuleEntity::getRuleId)
                .containsExactly(openEnded.getRuleId());
    }
}
