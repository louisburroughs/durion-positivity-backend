package com.positivity.price.internal.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.positivity.price.internal.entity.LocationPriceOverride;

/**
 * Location override repository.
 *
 * Issue: #51
 */
public interface LocationPriceOverrideRepository extends JpaRepository<LocationPriceOverride, UUID> {

    List<LocationPriceOverride> findAllByProductIdAndLocationIdOrderByEffectiveFromDesc(UUID productId, UUID locationId);

    @Query("""
            SELECT l FROM LocationPriceOverride l
            WHERE l.productId = :productId
            AND l.locationId = :locationId
            AND l.effectiveFrom <= :effectiveAt
            AND (l.effectiveTo IS NULL OR l.effectiveTo > :effectiveAt)
            ORDER BY l.effectiveFrom DESC
            """)
    List<LocationPriceOverride> findActiveAt(
            @Param("productId") UUID productId,
            @Param("locationId") UUID locationId,
            @Param("effectiveAt") Instant effectiveAt,
            Pageable pageable);

    default Optional<LocationPriceOverride> findActiveAt(UUID productId, UUID locationId, Instant effectiveAt) {
        return findActiveAt(productId, locationId, effectiveAt, PageRequest.of(0, 1)).stream().findFirst();
    }

    @Query("""
            SELECT (COUNT(l) > 0) FROM LocationPriceOverride l
            WHERE l.productId = :productId
            AND l.locationId = :locationId
            AND (:excludeId IS NULL OR l.id <> :excludeId)
            AND (:effectiveTo IS NULL OR l.effectiveFrom < :effectiveTo)
            AND (l.effectiveTo IS NULL OR l.effectiveTo > :effectiveFrom)
            """)
    boolean existsOverlappingEffectiveWindow(
            @Param("productId") UUID productId,
            @Param("locationId") UUID locationId,
            @Param("effectiveFrom") Instant effectiveFrom,
            @Param("effectiveTo") Instant effectiveTo,
            @Param("excludeId") UUID excludeId);

    default boolean existsOverlappingEffectiveWindow(
            UUID productId,
            UUID locationId,
            Instant effectiveFrom,
            Instant effectiveTo) {
        return existsOverlappingEffectiveWindow(productId, locationId, effectiveFrom, effectiveTo, null);
    }
}
