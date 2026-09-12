package com.positivity.price.internal.repository;

import com.positivity.price.internal.entity.LocationPriceOverride;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Location override repository.
 *
 * Issue: #51
 */
public interface LocationPriceOverrideRepository
        extends JpaRepository<LocationPriceOverride, UUID>, JpaSpecificationExecutor<LocationPriceOverride> {

    List<LocationPriceOverride> findAllByProductIdAndLocationIdOrderByEffectiveFromDesc(
            UUID productId, UUID locationId);

    @Query("""
            SELECT l FROM LocationPriceOverride l
            WHERE l.productId = :productId
            AND l.locationId = :locationId
            AND l.currency = :currency
            AND l.effectiveFrom <= :effectiveAt
            AND (l.effectiveTo IS NULL OR l.effectiveTo > :effectiveAt)
            ORDER BY l.effectiveFrom DESC
            """)
    List<LocationPriceOverride> findActiveAt(
            @Param("productId") UUID productId,
            @Param("locationId") UUID locationId,
            @Param("currency") String currency,
            @Param("effectiveAt") Instant effectiveAt,
            Pageable pageable);

    default Optional<LocationPriceOverride> findActiveAt(
            UUID productId, UUID locationId, String currency, Instant effectiveAt) {
        return findActiveAt(productId, locationId, currency, effectiveAt, PageRequest.of(0, 1)).stream()
                .findFirst();
    }

    /**
     * Whether this product and location already carry an override whose effective window overlaps
     * the candidate one — the guard a create or an edit runs before it schedules a price.
     *
     * <p>The filter is an {@link EffectiveWindowOverlapSearch} specification rather than a JPQL
     * string of {@code (:param IS NULL OR …)} clauses: see that class for why the string form was
     * rejected by PostgreSQL for every call, supplied filter or not, while passing on H2 (issue
     * #1891).
     *
     * @param productId the product the candidate window prices
     * @param locationId the location the candidate window prices it at
     * @param effectiveFrom the candidate window's inclusive start
     * @param effectiveTo the candidate window's exclusive end, or null for "until further notice"
     * @param excludeId the override being edited, so that it does not overlap itself, or null for
     *     an override that does not exist yet
     * @return true when at least one other override of the same series overlaps
     */
    default boolean existsOverlappingEffectiveWindow(
            @NonNull UUID productId,
            @NonNull UUID locationId,
            @NonNull Instant effectiveFrom,
            @Nullable Instant effectiveTo,
            @Nullable UUID excludeId) {
        return exists(EffectiveWindowOverlapSearch.matching(
                productId, "locationId", locationId, effectiveFrom, effectiveTo, excludeId));
    }

    /** The overlap guard for an override that does not exist yet, so has nothing to exclude. */
    default boolean existsOverlappingEffectiveWindow(
            @NonNull UUID productId,
            @NonNull UUID locationId,
            @NonNull Instant effectiveFrom,
            @Nullable Instant effectiveTo) {
        return existsOverlappingEffectiveWindow(productId, locationId, effectiveFrom, effectiveTo, null);
    }
}
