package com.positivity.nhtsa.repository;

import com.positivity.nhtsa.entity.VehicleType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VehicleTypeRepository extends JpaRepository<VehicleType, Long> {
    List<VehicleType> findByMakeId(Long makeId);
}

