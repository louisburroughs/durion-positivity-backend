package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.NormalizedAvailability;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for normalized manufacturer availability records.
 *
 * Issue: CAP-170 (#46)
 */
public interface NormalizedAvailabilityRepository extends JpaRepository<NormalizedAvailability, UUID> {

    Optional<NormalizedAvailability> findByProductIdAndManufacturerIdAndAsOf(
            UUID productId, UUID manufacturerId, Instant asOf);

    /**
     * Most recent availability snapshot for a product across manufacturers (odoo-parity F3,
     * issue #1041): the pack-size seed source for a newly created replenishment policy's
     * {@code orderMultiple} default.
     */
    Optional<NormalizedAvailability> findFirstByProductIdOrderByAsOfDesc(UUID productId);

    /**
     * All availability snapshots for a product, newest first (odoo-parity F4, issue #1044):
     * the vendor-selection input — callers keep the first (latest) row per manufacturer.
     */
    List<NormalizedAvailability> findByProductIdOrderByAsOfDesc(UUID productId);

    @Query("""
            SELECT MIN(n.leadTimeDaysMin) AS minDays,
                   MAX(n.leadTimeDaysMax) AS maxDays,
                   MAX(n.asOf) AS asOf
            FROM NormalizedAvailability n
            WHERE n.productId = :productId
              AND (n.leadTimeDaysMin IS NOT NULL OR n.leadTimeDaysMax IS NOT NULL)
            """)
    LeadTimeAggregate findLeadTimeAggregateByProductId(@Param("productId") UUID productId);

    /**
     * One row per manufacturer the feed reports (CAP:550 S36, #2525): its distinct products and when it last reported.
     * Feeds the unmapped-feed-vendor list.
     */
    @Query("""
            SELECT n.manufacturerId AS feedVendorRef,
                   COUNT(DISTINCT n.productId) AS productCount,
                   MAX(n.asOf) AS lastReportedAt
            FROM NormalizedAvailability n
            GROUP BY n.manufacturerId
            """)
    List<FeedVendorSummary> summarizeManufacturers();

    interface LeadTimeAggregate {
        Integer getMinDays();

        Integer getMaxDays();

        Instant getAsOf();
    }
}
