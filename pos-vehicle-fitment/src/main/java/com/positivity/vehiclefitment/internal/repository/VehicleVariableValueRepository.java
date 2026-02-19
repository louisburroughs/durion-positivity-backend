package com.positivity.vehiclefitment.internal.repository;

import com.positivity.vehiclefitment.internal.entity.VehicleVariableValue;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface VehicleVariableValueRepository extends JpaRepository<VehicleVariableValue, UUID> {
    List<VehicleVariableValue> findByVariableId(UUID variableId);
}

