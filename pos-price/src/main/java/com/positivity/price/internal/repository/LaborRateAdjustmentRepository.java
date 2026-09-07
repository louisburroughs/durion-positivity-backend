package com.positivity.price.internal.repository;

import com.positivity.price.internal.entity.LaborRateAdjustment;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LaborRateAdjustmentRepository extends JpaRepository<LaborRateAdjustment, UUID> {

    /**
     * Matrix steps in force at {@code at} whose code the caller opted into and whose scope could
     * answer for this location and category. Ordered by {@code sequence} because percentage
     * steps compound, so the order is part of the answer.
     */
    @Query("""
            SELECT a FROM LaborRateAdjustment a
            WHERE a.adjustmentCode IN :codes
              AND (a.locationId IS NULL OR a.locationId = :locationId)
              AND (a.operationCategory IS NULL OR CAST(a.operationCategory AS string) = :categoryName)
              AND a.effectiveFrom <= :at
              AND (a.effectiveTo IS NULL OR a.effectiveTo > :at)
            ORDER BY a.sequence ASC, a.adjustmentCode ASC
            """)
    @NonNull
    List<LaborRateAdjustment> findApplicable(
            @Param("codes") Collection<String> codes,
            @Param("locationId") UUID locationId,
            @Param("categoryName") String categoryName,
            @Param("at") Instant at);

    @NonNull
    List<LaborRateAdjustment> findAllByOrderBySequenceAscAdjustmentCodeAsc();
}
