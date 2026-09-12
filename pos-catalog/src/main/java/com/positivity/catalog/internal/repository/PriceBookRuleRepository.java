package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.PriceBookRuleConditionType;
import com.positivity.catalog.internal.entity.PriceBookRuleEntity;
import com.positivity.catalog.internal.entity.PriceBookRuleStatus;
import com.positivity.catalog.internal.entity.PriceBookRuleTargetType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PriceBookRuleRepository
        extends JpaRepository<PriceBookRuleEntity, UUID>, JpaSpecificationExecutor<PriceBookRuleEntity> {

    @Query("""
            select r from PriceBookRuleEntity r
            where r.priceBook.priceBookId = :priceBookId
            order by r.priority desc, r.effectiveStartAt desc, r.ruleId asc
            """)
    List<PriceBookRuleEntity> findAllByPriceBookId(@Param("priceBookId") UUID priceBookId);

    /**
     * The active rules of one price book that would overlap a candidate rule's window on the same
     * target and condition — the check a create or an update is admitted by.
     *
     * <p>The filter is a {@link PriceBookRuleConflictSearch} specification rather than a JPQL string
     * of {@code (:param IS NULL OR …)} clauses: see that class for why the string form returned 500
     * from PostgreSQL for every call while passing on H2 (issue #1891).
     *
     * @param priceBookId the book the candidate rule belongs to
     * @param targetType the candidate's target type
     * @param targetId the candidate's target, or null for an untargeted rule — matched null-safely,
     *     because two untargeted rules do conflict with each other
     * @param conditionType the candidate's condition type
     * @param conditionValue the candidate's condition value, or null — matched null-safely
     * @param windowStart inclusive lower bound of the candidate's window, or null for no lower bound
     * @param windowEnd inclusive upper bound of the candidate's window
     * @param excludeRuleId the rule being updated, so it cannot conflict with itself, or null
     * @return the conflicting rules, empty when the candidate is admissible
     */
    @NonNull
    default List<PriceBookRuleEntity> findConflicts(
            @NonNull UUID priceBookId,
            @NonNull PriceBookRuleTargetType targetType,
            @Nullable UUID targetId,
            @NonNull PriceBookRuleConditionType conditionType,
            @Nullable String conditionValue,
            @Nullable Instant windowStart,
            @NonNull Instant windowEnd,
            @Nullable UUID excludeRuleId) {
        return findAll(PriceBookRuleConflictSearch.matching(
                priceBookId,
                targetType,
                targetId,
                conditionType,
                conditionValue,
                windowStart,
                windowEnd,
                excludeRuleId));
    }

    @Query("""
            select r from PriceBookRuleEntity r
            where r.priceBook.priceBookId in :priceBookIds
              and r.status = :status
              and r.effectiveStartAt <= :asOf
              and (r.effectiveEndAt is null or r.effectiveEndAt >= :asOf)
            """)
    List<PriceBookRuleEntity> findActiveRulesForBooks(
            @Param("priceBookIds") List<UUID> priceBookIds,
            @Param("status") PriceBookRuleStatus status,
            @Param("asOf") Instant asOf);
}
