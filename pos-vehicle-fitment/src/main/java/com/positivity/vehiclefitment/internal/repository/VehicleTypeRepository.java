package com.positivity.vehiclefitment.internal.repository;

import com.positivity.vehiclefitment.internal.entity.VehicleType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleTypeRepository extends JpaRepository<VehicleType, UUID> {
    List<VehicleType> findByMakeId(UUID makeId);
}
