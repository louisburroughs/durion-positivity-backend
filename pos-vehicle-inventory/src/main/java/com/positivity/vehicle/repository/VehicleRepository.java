package com.positivity.vehicle.repository;

import com.positivity.vehicle.model.VehicleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VehicleRepository extends JpaRepository<VehicleEntity, Long> {
    Optional<VehicleEntity> findByVin(String vin);
    void deleteByVin(String vin);
}
