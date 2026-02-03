package com.positivity.vehicle.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.positivity.vehicle.internal.entity.VehicleEntity;

import java.util.Optional;

public interface VehicleRepository extends JpaRepository<VehicleEntity, Long> {
    Optional<VehicleEntity> findByVin(String vin);

    void deleteByVin(String vin);
}
