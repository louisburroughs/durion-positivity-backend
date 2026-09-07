package com.positivity.price.internal.repository;

import com.positivity.price.internal.entity.LaborRate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LaborRateRepository extends JpaRepository<LaborRate, UUID> {

    /**
     * Every rate in force at {@code at} whose scope could answer for this location and category,
     * widest scope included. Resolution picks the most specific of these in Java rather than
     * asking the database four times — the candidate set is at most four rows.
     *
     * <p>A null {@code locationId} or {@code categoryName} argument matches only the rows that
     * are themselves null there, which is exactly right: a caller who names no location is
     * asking for the platform default.
     */
    @Query("""
            SELECT r FROM LaborRate r
            WHERE (r.locationId IS NULL OR r.locationId = :locationId)
              AND (r.operationCategory IS NULL OR CAST(r.operationCategory AS string) = :categoryName)
              AND r.effectiveFrom <= :at
              AND (r.effectiveTo IS NULL OR r.effectiveTo > :at)
            """)
    @NonNull
    List<LaborRate> findCandidates(
            @Param("locationId") UUID locationId, @Param("categoryName") String categoryName, @Param("at") Instant at);

    @NonNull
    List<LaborRate> findAllByOrderByEffectiveFromDesc();
}
