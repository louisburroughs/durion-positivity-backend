package com.positivity.nhtsa.repository;

import com.positivity.nhtsa.entity.VehicleVariable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleVariableRepository extends JpaRepository<VehicleVariable, Long> {
}

