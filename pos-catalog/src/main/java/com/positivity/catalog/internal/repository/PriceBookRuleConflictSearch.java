package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.PriceBookRuleConditionType;
import com.positivity.catalog.internal.entity.PriceBookRuleEntity;
import com.positivity.catalog.internal.entity.PriceBookRuleStatus;
import com.positivity.catalog.internal.entity.PriceBookRuleTargetType;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The overlap check a price-book rule is admitted by, built as a {@link Specification} so that an
 * absent filter emits no SQL at all — see {@link FactReplaySearch} for why the {@code (:param IS
 * NULL OR …)} JPQL this replaces made PostgreSQL reject the statement at parse time with {@code
 * could not determine data type of parameter $n} (the defect of issue #1891). Here the untyped
 * placeholder was {@code $10}, the {@code windowStart} {@link Instant}, so creating or updating a
 * rule was a 500 on every call.
 *
 * <h2>Two clause shapes that read alike and are not</h2>
 *
 * <ul>
 *   <li>{@code targetId} and {@code conditionValue} are <strong>null-safe equality</strong>, not
 *       optional filters: a rule with no target conflicts with another rule with no target, so a
 *       null argument must match the rows whose column is null rather than match every row. The
 *       predicate therefore becomes {@code IS NULL} when the argument is absent.
 *   <li>{@code windowStart} and {@code excludeRuleId} are genuinely <strong>optional</strong>: a
 *       null argument contributes no predicate.
 * </ul>
 *
 * <h2>The window is half-open at neither end</h2>
 *
 * Rule windows are inclusive of both bounds, and an open-ended rule ({@code effectiveEndAt} null)
 * runs forever — so it overlaps any window that starts at or after its own start. That is why the
 * upper-bound clause admits a null {@code effectiveEndAt} alongside the comparison.
 */
final class PriceBookRuleConflictSearch {

    private static final String PRICE_BOOK = "priceBook";
    private static final String PRICE_BOOK_ID = "priceBookId";
    private static final String STATUS = "status";
    private static final String TARGET_TYPE = "targetType";
    private static final String TARGET_ID = "targetId";
    private static final String CONDITION_TYPE = "conditionType";
    private static final String CONDITION_VALUE = "conditionValue";
    private static final String EFFECTIVE_START_AT = "effectiveStartAt";
    private static final String EFFECTIVE_END_AT = "effectiveEndAt";
    private static final String RULE_ID = "ruleId";

    private PriceBookRuleConflictSearch() {}

    /**
     * Rules of one price book that would overlap the candidate's window on the same target and
     * condition.
     *
     * @param priceBookId the book the candidate rule belongs to
     * @param targetType the candidate's target type
     * @param targetId the candidate's target, or null for a rule that targets nothing — matched
     *     null-safely, so a null argument finds the untargeted rules
     * @param conditionType the candidate's condition type
     * @param conditionValue the candidate's condition value, or null — matched null-safely
     * @param windowStart inclusive lower bound of the candidate's window, or null for no lower bound
     * @param windowEnd inclusive upper bound of the candidate's window; the caller substitutes a far
     *     future instant for an open-ended candidate
     * @param excludeRuleId the rule being updated, excluded so it cannot conflict with itself, or
     *     null when the candidate is new
     * @return the specification matching the conflicting rules
     */
    @NonNull
    static Specification<PriceBookRuleEntity> matching(
            @NonNull UUID priceBookId,
            @NonNull PriceBookRuleTargetType targetType,
            @Nullable UUID targetId,
            @NonNull PriceBookRuleConditionType conditionType,
            @Nullable String conditionValue,
            @Nullable Instant windowStart,
            @NonNull Instant windowEnd,
            @Nullable UUID excludeRuleId) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(8);
            predicates.add(builder.equal(root.get(PRICE_BOOK).get(PRICE_BOOK_ID), priceBookId));
            // Only an ACTIVE rule can be conflicted with. This was `<> INACTIVE`, which is the same
            // set today but says something weaker: PriceBookRuleStatus carries a third constant,
            // NOT_APPLICABLE_MISSING_BASE, and `<> INACTIVE` would let such a rule block a create or
            // update. Nothing can produce that status — PriceBookServiceImpl writes ACTIVE on create
            // (:119) and INACTIVE on deactivate (:157), those are its only two writes, and
            // PriceBookRuleCreateRequestDto has no status field, so no client can supply one. The
            // constant is reachable only from the baseline's check constraint and the response
            // schema. Naming ACTIVE keeps this query correct whichever way that constant is
            // resolved, and matches how findActiveRulesForBooks states its own filter.
            predicates.add(builder.equal(root.get(STATUS), PriceBookRuleStatus.ACTIVE));
            predicates.add(builder.equal(root.get(TARGET_TYPE), targetType));
            predicates.add(
                    targetId == null
                            ? builder.isNull(root.get(TARGET_ID))
                            : builder.equal(root.get(TARGET_ID), targetId));
            predicates.add(builder.equal(root.get(CONDITION_TYPE), conditionType));
            predicates.add(
                    conditionValue == null
                            ? builder.isNull(root.get(CONDITION_VALUE))
                            : builder.equal(root.get(CONDITION_VALUE), conditionValue));
            predicates.add(builder.lessThanOrEqualTo(root.get(EFFECTIVE_START_AT), windowEnd));
            if (windowStart != null) {
                predicates.add(builder.or(
                        builder.isNull(root.get(EFFECTIVE_END_AT)),
                        builder.greaterThanOrEqualTo(root.get(EFFECTIVE_END_AT), windowStart)));
            }
            if (excludeRuleId != null) {
                predicates.add(builder.notEqual(root.get(RULE_ID), excludeRuleId));
            }
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
