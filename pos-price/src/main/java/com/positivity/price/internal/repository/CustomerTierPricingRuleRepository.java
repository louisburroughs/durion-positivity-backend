package com.positivity.price.internal.repository;

import com.positivity.price.internal.entity.CustomerTierPricingRule;
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
 * Customer tier rule repository.
 *
 * Issue: #51
 */
public interface CustomerTierPricingRuleRepository
        extends JpaRepository<CustomerTierPricingRule, UUID>, JpaSpecificationExecutor<CustomerTierPricingRule> {

    List<CustomerTierPricingRule> findAllByProductIdAndCustomerTierIdOrderByEffectiveFromDesc(
            UUID productId, UUID customerTierId);

    @Query("""
            SELECT c FROM CustomerTierPricingRule c
            WHERE c.productId = :productId
            AND c.customerTierId = :customerTierId
            AND c.effectiveFrom <= :effectiveAt
            AND (c.effectiveTo IS NULL OR c.effectiveTo > :effectiveAt)
            ORDER BY c.effectiveFrom DESC
            """)
    List<CustomerTierPricingRule> findActiveAt(
            @Param("productId") UUID productId,
            @Param("customerTierId") UUID customerTierId,
            @Param("effectiveAt") Instant effectiveAt,
            Pageable pageable);

    default Optional<CustomerTierPricingRule> findActiveAt(UUID productId, UUID customerTierId, Instant effectiveAt) {
        return findActiveAt(productId, customerTierId, effectiveAt, PageRequest.of(0, 1)).stream()
                .findFirst();
    }

    /**
     * Whether this product and tier already carry a rule whose effective window overlaps the
     * candidate one — the guard a create or an edit runs before it schedules a discount.
     *
     * <p>The filter is an {@link EffectiveWindowOverlapSearch} specification rather than a JPQL
     * string of {@code (:param IS NULL OR …)} clauses: see that class for why the string form was
     * rejected by PostgreSQL for every call, supplied filter or not, while passing on H2 (issue
     * #1891).
     *
     * @param productId the product the candidate window prices
     * @param customerTierId the tier the candidate window prices it for
     * @param effectiveFrom the candidate window's inclusive start
     * @param effectiveTo the candidate window's exclusive end, or null for "until further notice"
     * @param excludeId the rule being edited, so that it does not overlap itself, or null for a
     *     rule that does not exist yet
     * @return true when at least one other rule of the same series overlaps
     */
    default boolean existsOverlappingEffectiveWindow(
            @NonNull UUID productId,
            @NonNull UUID customerTierId,
            @NonNull Instant effectiveFrom,
            @Nullable Instant effectiveTo,
            @Nullable UUID excludeId) {
        return exists(EffectiveWindowOverlapSearch.matching(
                productId, "customerTierId", customerTierId, effectiveFrom, effectiveTo, excludeId));
    }

    /** The overlap guard for a rule that does not exist yet, so has nothing to exclude. */
    default boolean existsOverlappingEffectiveWindow(
            @NonNull UUID productId,
            @NonNull UUID customerTierId,
            @NonNull Instant effectiveFrom,
            @Nullable Instant effectiveTo) {
        return existsOverlappingEffectiveWindow(productId, customerTierId, effectiveFrom, effectiveTo, null);
    }
}
