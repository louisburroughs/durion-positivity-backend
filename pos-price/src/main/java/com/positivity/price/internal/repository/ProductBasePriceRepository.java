package com.positivity.price.internal.repository;

import com.positivity.price.internal.model.ProductBasePrice;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Product base price repository.
 *
 * Issue: #51
 */
public interface ProductBasePriceRepository extends JpaRepository<ProductBasePrice, UUID> {

    Optional<ProductBasePrice> findByProductIdAndEffectiveToIsNull(UUID productId);

    @Query("""
            SELECT p FROM ProductBasePrice p
            WHERE p.productId = :productId
            AND p.effectiveFrom <= :effectiveAt
            AND (p.effectiveTo IS NULL OR p.effectiveTo > :effectiveAt)
            ORDER BY p.effectiveFrom DESC
            """)
    Optional<ProductBasePrice> findActiveAt(@Param("productId") UUID productId,
            @Param("effectiveAt") Instant effectiveAt);
}
