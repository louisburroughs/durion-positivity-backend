package com.positivity.vehicle.internal.service;

import com.positivity.vehicle.internal.dao.VehicleDao;
import com.positivity.vehicle.internal.entity.VehicleEntity;
import com.positivity.vehicle.internal.repository.VehicleRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class VehicleDaoImpl implements VehicleDao {
    private final VehicleRepository vehicleRepository;

    public VehicleDaoImpl(VehicleRepository vehicleRepository) {
        this.vehicleRepository = vehicleRepository;
    }

    @Override
    public VehicleEntity save(VehicleEntity vehicle) {
        return vehicleRepository.save(vehicle);
    }

    @Override
    public Optional<VehicleEntity> findById(UUID id) {
        return vehicleRepository.findById(id);
    }

    @Override
    public List<VehicleEntity> findAll() {
        return vehicleRepository.findAll();
    }

    @Override
    public void deleteById(UUID id) {
        vehicleRepository.deleteById(id);
    }

    @Override
    public Optional<VehicleEntity> findByVIN(String vin) {
        return vehicleRepository.findByVin(vin);
    }

    @Override
    public void deleteByVIN(String vin) {
        vehicleRepository.deleteByVin(vin);
    }
}
