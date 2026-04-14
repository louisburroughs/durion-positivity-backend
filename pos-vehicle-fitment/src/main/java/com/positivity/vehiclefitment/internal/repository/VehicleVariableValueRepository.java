package com.positivity.vehiclefitment.internal.repository;

import com.positivity.vehiclefitment.internal.entity.VehicleVariableValue;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleVariableValueRepository extends JpaRepository<VehicleVariableValue, UUID> {
    List<VehicleVariableValue> findByVariable_Id(UUID variableId);
}
