package com.positivity.vehiclefitment.repository;

import com.positivity.vehiclefitment.entity.VehicleType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VehicleTypeRepository extends JpaRepository<VehicleType, Long> {
    List<VehicleType> findByMakeId(Long makeId);
}

