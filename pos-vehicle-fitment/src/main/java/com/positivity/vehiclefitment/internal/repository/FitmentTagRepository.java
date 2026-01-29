package com.positivity.vehiclefitment.internal.repository;

import com.positivity.vehiclefitment.internal.entity.FitmentTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for FitmentTag entities.
 */
@Repository
public interface FitmentTagRepository extends JpaRepository<FitmentTag, Long> {
}
