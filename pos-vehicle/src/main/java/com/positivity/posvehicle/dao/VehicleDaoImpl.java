package com.positivity.posvehicle.dao;

import com.positivity.posvehicle.model.VehicleEntity;
import com.positivity.posvehicle.repository.VehicleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class VehicleDaoImpl implements VehicleDao {
    private final VehicleRepository vehicleRepository;

    @Autowired
    public VehicleDaoImpl(VehicleRepository vehicleRepository) {
        this.vehicleRepository = vehicleRepository;
    }

    @Override
    public VehicleEntity save(VehicleEntity vehicle) {
        return vehicleRepository.save(vehicle);
    }

    @Override
    public Optional<VehicleEntity> findById(Long id) {
        return vehicleRepository.findById(id);
    }

    @Override
    public List<VehicleEntity> findAll() {
        return vehicleRepository.findAll();
    }

    @Override
    public void deleteById(Long id) {
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
