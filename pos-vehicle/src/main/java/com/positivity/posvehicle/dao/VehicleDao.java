package com.positivity.posvehicle.dao;

import com.positivity.posvehicle.model.VehicleEntity;
import java.util.List;
import java.util.Optional;

public interface VehicleDao {
    VehicleEntity save(VehicleEntity vehicle);
    Optional<VehicleEntity> findById(Long id);
    Optional<VehicleEntity> findByVIN(String vin);
    List<VehicleEntity> findAll();
    void deleteById(Long id);
    void deleteByVIN(String vin);
}
