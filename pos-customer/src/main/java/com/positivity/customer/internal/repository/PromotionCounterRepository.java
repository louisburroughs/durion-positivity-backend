package com.positivity.customer.internal.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.positivity.customer.internal.entity.PromotionCounter;

@Repository
public interface PromotionCounterRepository extends JpaRepository<PromotionCounter, UUID> {

    Optional<PromotionCounter> findByPromotionId(UUID promotionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PromotionCounter counter set counter.totalUsageCount = counter.totalUsageCount + 1 where counter.promotionId = :promotionId")
    int incrementTotalUsageCount(@Param("promotionId") UUID promotionId);
}
