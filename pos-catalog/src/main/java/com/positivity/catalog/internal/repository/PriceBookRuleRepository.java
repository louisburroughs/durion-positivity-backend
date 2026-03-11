package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.PriceBookRuleEntity;
import com.positivity.catalog.internal.entity.PriceBookRuleConditionType;
import com.positivity.catalog.internal.entity.PriceBookRuleStatus;
import com.positivity.catalog.internal.entity.PriceBookRuleTargetType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PriceBookRuleRepository extends JpaRepository<PriceBookRuleEntity, UUID> {

    @Query("""
            select r from PriceBookRuleEntity r
            where r.priceBook.priceBookId = :priceBookId
            order by r.priority desc, r.effectiveStartAt desc, r.ruleId asc
            """)
    List<PriceBookRuleEntity> findAllByPriceBookId(@Param("priceBookId") UUID priceBookId);

    @Query("""
            select r from PriceBookRuleEntity r
            where r.priceBook.priceBookId = :priceBookId
              and r.status <> com.positivity.catalog.internal.entity.PriceBookRuleStatus.INACTIVE
              and r.targetType = :targetType
              and ((r.targetId is null and :targetId is null) or r.targetId = :targetId)
              and r.conditionType = :conditionType
              and ((r.conditionValue is null and :conditionValue is null) or r.conditionValue = :conditionValue)
              and r.effectiveStartAt <= :windowEnd
              and (:windowStart is null or r.effectiveEndAt is null or r.effectiveEndAt >= :windowStart)
              and (:excludeRuleId is null or r.ruleId <> :excludeRuleId)
            """)
    List<PriceBookRuleEntity> findConflicts(
            @Param("priceBookId") UUID priceBookId,
            @Param("targetType") PriceBookRuleTargetType targetType,
            @Param("targetId") UUID targetId,
            @Param("conditionType") PriceBookRuleConditionType conditionType,
            @Param("conditionValue") String conditionValue,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("excludeRuleId") UUID excludeRuleId);

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
