package com.positivity.vehicle.internal.dao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.positivity.vehicle.internal.entity.VehicleEntity;

public interface VehicleDao {
    VehicleEntity save(VehicleEntity vehicle);

    Optional<VehicleEntity> findById(UUID id);

    Optional<VehicleEntity> findByVIN(String vin);

    List<VehicleEntity> findAll();

    void deleteById(UUID id);

    void deleteByVIN(String vin);
}
