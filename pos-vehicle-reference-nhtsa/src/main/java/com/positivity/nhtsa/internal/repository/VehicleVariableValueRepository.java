package com.positivity.nhtsa.internal.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.positivity.nhtsa.internal.entity.VehicleVariableValue;

public interface VehicleVariableValueRepository extends JpaRepository<VehicleVariableValue, UUID> {
    List<VehicleVariableValue> findByVariable_Id(UUID variableId);
}
