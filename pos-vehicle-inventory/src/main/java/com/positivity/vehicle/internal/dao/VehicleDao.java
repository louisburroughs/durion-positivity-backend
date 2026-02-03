package com.positivity.vehicle.internal.dao;

import java.util.List;
import java.util.Optional;

import com.positivity.vehicle.internal.entity.VehicleEntity;

public interface VehicleDao {
    VehicleEntity save(VehicleEntity vehicle);

    Optional<VehicleEntity> findById(Long id);

    Optional<VehicleEntity> findByVIN(String vin);

    List<VehicleEntity> findAll();

    void deleteById(Long id);

    void deleteByVIN(String vin);
}
