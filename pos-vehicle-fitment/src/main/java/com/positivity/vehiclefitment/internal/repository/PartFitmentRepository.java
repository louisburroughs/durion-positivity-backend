package com.positivity.vehiclefitment.internal.repository;

import com.positivity.vehiclefitment.internal.entity.PartFitmentEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartFitmentRepository extends JpaRepository<PartFitmentEntity, UUID> {
}
