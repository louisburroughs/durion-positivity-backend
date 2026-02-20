package com.positivity.price.internal.repository;

import com.positivity.price.internal.model.LocationPriceOverride;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Location override repository.
 *
 * Issue: #51
 */
public interface LocationPriceOverrideRepository extends JpaRepository<LocationPriceOverride, UUID> {

    Optional<LocationPriceOverride> findByProductIdAndLocationId(UUID productId, UUID locationId);

    @Query("""
            SELECT l FROM LocationPriceOverride l
            WHERE l.productId = :productId
            AND l.locationId = :locationId
            AND l.effectiveFrom <= :effectiveAt
            AND (l.effectiveTo IS NULL OR l.effectiveTo > :effectiveAt)
            ORDER BY l.effectiveFrom DESC
            """)
    Optional<LocationPriceOverride> findActiveAt(
            @Param("productId") UUID productId,
            @Param("locationId") UUID locationId,
            @Param("effectiveAt") Instant effectiveAt);
}
