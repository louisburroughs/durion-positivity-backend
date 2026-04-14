package com.positivity.vehicle.internal.dao;

import com.positivity.vehicle.internal.entity.VehicleEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleDao {
    VehicleEntity save(VehicleEntity vehicle);

    Optional<VehicleEntity> findById(UUID id);

    Optional<VehicleEntity> findByVIN(String vin);

    List<VehicleEntity> findAll();

    void deleteById(UUID id);

    void deleteByVIN(String vin);
}
