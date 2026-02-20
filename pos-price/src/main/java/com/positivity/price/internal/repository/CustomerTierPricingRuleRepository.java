package com.positivity.price.internal.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.positivity.price.internal.entity.CustomerTierPricingRule;

/**
 * Customer tier rule repository.
 *
 * Issue: #51
 */
public interface CustomerTierPricingRuleRepository extends JpaRepository<CustomerTierPricingRule, UUID> {

    Optional<CustomerTierPricingRule> findByProductIdAndCustomerTierId(UUID productId, UUID customerTierId);

    @Query("""
            SELECT c FROM CustomerTierPricingRule c
            WHERE c.productId = :productId
            AND c.customerTierId = :customerTierId
            AND c.effectiveFrom <= :effectiveAt
            AND (c.effectiveTo IS NULL OR c.effectiveTo > :effectiveAt)
            ORDER BY c.effectiveFrom DESC
            """)
    Optional<CustomerTierPricingRule> findActiveAt(
            @Param("productId") UUID productId,
            @Param("customerTierId") UUID customerTierId,
            @Param("effectiveAt") Instant effectiveAt);
}
