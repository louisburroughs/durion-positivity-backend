package com.positivity.vehicle.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.positivity.vehicle.internal.entity.VehicleEntity;

import java.util.Optional;
import java.util.UUID;

public interface VehicleRepository extends JpaRepository<VehicleEntity, UUID> {
    Optional<VehicleEntity> findByVin(String vin);

    void deleteByVin(String vin);
}
