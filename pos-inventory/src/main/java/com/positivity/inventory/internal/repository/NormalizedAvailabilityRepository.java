package com.positivity.inventory.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.positivity.inventory.internal.entity.NormalizedAvailability;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for normalized manufacturer availability records.
 *
 * Issue: CAP-170 (#46)
 */
public interface NormalizedAvailabilityRepository extends JpaRepository<NormalizedAvailability, UUID> {

    Optional<NormalizedAvailability> findByProductIdAndManufacturerIdAndAsOf(UUID productId, UUID manufacturerId,
            Instant asOf);

    @Query("""
            SELECT MIN(n.leadTimeDaysMin) AS minDays,
                   MAX(n.leadTimeDaysMax) AS maxDays,
                   MAX(n.asOf) AS asOf
            FROM NormalizedAvailability n
            WHERE n.productId = :productId
              AND (n.leadTimeDaysMin IS NOT NULL OR n.leadTimeDaysMax IS NOT NULL)
            """)
    LeadTimeAggregate findLeadTimeAggregateByProductId(@Param("productId") UUID productId);

    interface LeadTimeAggregate {
        Integer getMinDays();

        Integer getMaxDays();

        Instant getAsOf();
    }
}
