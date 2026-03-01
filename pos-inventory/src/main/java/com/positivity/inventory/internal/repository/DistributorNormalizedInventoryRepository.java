package com.positivity.inventory.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.positivity.inventory.internal.entity.DistributorNormalizedInventory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for normalized distributor inventory records.
 *
 * Issue: CAP-170 (#47)
 */
public interface DistributorNormalizedInventoryRepository extends JpaRepository<DistributorNormalizedInventory, UUID> {

    Optional<DistributorNormalizedInventory> findByDistributorIdAndDistributorSku(String distributorId,
            String distributorSku);

    @Query("""
            SELECT MIN(d.leadTimeDaysMin) AS minDays,
                   MAX(d.leadTimeDaysMax) AS maxDays,
                   MAX(d.updatedAt) AS asOf
            FROM DistributorNormalizedInventory d
            WHERE d.productId = :productId
              AND (d.leadTimeDaysMin IS NOT NULL OR d.leadTimeDaysMax IS NOT NULL)
            """)
    LeadTimeAggregate findLeadTimeAggregateByProductId(@Param("productId") UUID productId);

    interface LeadTimeAggregate {
        Integer getMinDays();

        Integer getMaxDays();

        Instant getAsOf();
    }
}
