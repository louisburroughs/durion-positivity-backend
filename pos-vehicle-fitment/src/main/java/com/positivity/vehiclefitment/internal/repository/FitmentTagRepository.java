package com.positivity.vehiclefitment.internal.repository;

import com.positivity.vehiclefitment.internal.entity.FitmentTag;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for FitmentTag entities.
 */
public interface FitmentTagRepository extends JpaRepository<FitmentTag, UUID> {}
