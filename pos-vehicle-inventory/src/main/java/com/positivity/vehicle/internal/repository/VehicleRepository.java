package com.positivity.vehicle.internal.repository;

import com.positivity.vehicle.internal.model.VehicleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VehicleRepository extends JpaRepository<VehicleEntity, Long> {
    Optional<VehicleEntity> findByVin(String vin);
    void deleteByVin(String vin);
}
