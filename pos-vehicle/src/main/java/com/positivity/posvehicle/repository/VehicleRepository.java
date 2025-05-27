package com.positivity.posvehicle.repository;

import com.positivity.posvehicle.model.VehicleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VehicleRepository extends JpaRepository<VehicleEntity, Long> {
    Optional<VehicleEntity> findByVin(String vin);
    void deleteByVin(String vin);
}
