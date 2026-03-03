package com.positivity.price.internal.repository;

import com.positivity.price.internal.entity.PromotionOffer;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromotionOfferRepository extends JpaRepository<PromotionOffer, UUID> {

    Optional<PromotionOffer> findByPromoCode(String promoCode);

    boolean existsByPromoCode(String promoCode);
}
