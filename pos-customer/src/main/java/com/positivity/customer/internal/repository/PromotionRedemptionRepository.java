package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.PromotionRedemption;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PromotionRedemptionRepository extends JpaRepository<PromotionRedemption, UUID> {

    List<PromotionRedemption> findByCustomerId(UUID customerId);

    boolean existsByPromotionIdAndWorkorderId(UUID promotionId, UUID workorderId);
}
