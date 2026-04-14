package com.positivity.nhtsa.internal.repository;

import com.positivity.nhtsa.internal.entity.VehicleVariable;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleVariableRepository extends JpaRepository<VehicleVariable, UUID> {}
