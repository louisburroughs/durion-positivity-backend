package com.positivity.price.internal.repository;

import com.positivity.price.internal.entity.PromotionOffer;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PromotionOfferRepository extends JpaRepository<PromotionOffer, UUID> {

    Optional<PromotionOffer> findByPromoCode(String promoCode);

    boolean existsByPromoCode(String promoCode);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PromotionOffer offer
               set offer.usageCount = offer.usageCount + 1
             where offer.promotionOfferId = :promotionOfferId
               and (offer.usageLimit is null or offer.usageCount < offer.usageLimit)
            """)
    int incrementUsageCountIfUnderLimit(@Param("promotionOfferId") UUID promotionOfferId);
}
