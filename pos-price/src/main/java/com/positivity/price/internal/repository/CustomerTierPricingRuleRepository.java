package com.positivity.price.internal.repository;

import com.positivity.price.internal.entity.CustomerTierPricingRule;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Customer tier rule repository.
 *
 * Issue: #51
 */
public interface CustomerTierPricingRuleRepository extends JpaRepository<CustomerTierPricingRule, UUID> {

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

    @Query("""
            SELECT (COUNT(c) > 0) FROM CustomerTierPricingRule c
            WHERE c.productId = :productId
            AND c.customerTierId = :customerTierId
            AND (:excludeId IS NULL OR c.id <> :excludeId)
            AND (:effectiveTo IS NULL OR c.effectiveFrom < :effectiveTo)
            AND (c.effectiveTo IS NULL OR c.effectiveTo > :effectiveFrom)
            """)
    boolean existsOverlappingEffectiveWindow(
            @Param("productId") UUID productId,
            @Param("customerTierId") UUID customerTierId,
            @Param("effectiveFrom") Instant effectiveFrom,
            @Param("effectiveTo") Instant effectiveTo,
            @Param("excludeId") UUID excludeId);

    default boolean existsOverlappingEffectiveWindow(
            UUID productId, UUID customerTierId, Instant effectiveFrom, Instant effectiveTo) {
        return existsOverlappingEffectiveWindow(productId, customerTierId, effectiveFrom, effectiveTo, null);
    }
}
