package com.positivity.vehiclefitment.repository;

import com.positivity.vehiclefitment.entity.VehicleVariableValue;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VehicleVariableValueRepository extends JpaRepository<VehicleVariableValue, Long> {
    List<VehicleVariableValue> findByVariableId(Long variableId);
}

