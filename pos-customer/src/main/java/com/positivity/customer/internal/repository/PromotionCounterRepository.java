package com.positivity.customer.internal.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.customer.internal.entity.PromotionCounter;

@Repository
public interface PromotionCounterRepository extends JpaRepository<PromotionCounter, UUID> {

    Optional<PromotionCounter> findByPromotionId(UUID promotionId);
}