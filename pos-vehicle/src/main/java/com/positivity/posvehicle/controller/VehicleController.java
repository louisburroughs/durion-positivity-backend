package com.positivity.posvehicle.controller;

import com.positivity.posvehicle.dao.VehicleDao;
import com.positivity.posvehicle.model.VehicleEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {
    private final VehicleDao vehicleDao;

    @Autowired
    public VehicleController(VehicleDao vehicleDao) {
        this.vehicleDao = vehicleDao;
    }

    @PostMapping
    public ResponseEntity<VehicleEntity> createVehicle(@RequestBody VehicleEntity vehicle) {
        VehicleEntity saved = vehicleDao.save(vehicle);
        log.info("Created vehicle with id {}", saved.getId());
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{id}")
    public ResponseEntity<VehicleEntity> getVehicle(@PathVariable Long id) {
        Optional<VehicleEntity> vehicle = vehicleDao.findById(id);
        return vehicle.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<VehicleEntity> getAllVehicles() {
        return vehicleDao.findAll();
    }

    @PutMapping("/{id}")
    public ResponseEntity<VehicleEntity> updateVehicle(@PathVariable Long id, @RequestBody VehicleEntity updated) {
        Optional<VehicleEntity> existing = vehicleDao.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        VehicleEntity vehicle = existing.get();
        vehicle.setMake(updated.getMake());
        vehicle.setModel(updated.getModel());
        vehicle.setYear(updated.getYear());
        VehicleEntity saved = vehicleDao.save(vehicle);
        log.info("Updated vehicle with id {}", saved.getId());
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteVehicle(@PathVariable Long id) {
        if (vehicleDao.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        vehicleDao.deleteById(id);
        log.info("Deleted vehicle with id {}", id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/vin/{vin}")
    public ResponseEntity<VehicleEntity> createVehicleByVIN(@PathVariable String vin, @RequestBody VehicleEntity vehicle) {
        vehicle.setVIN(vin);
        VehicleEntity saved = vehicleDao.save(vehicle);
        log.info("Created vehicle with VIN {}", saved.getVIN());
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/vin/{vin}")
    public ResponseEntity<VehicleEntity> getVehicleByVIN(@PathVariable String vin) {
        Optional<VehicleEntity> vehicle = vehicleDao.findByVIN(vin);
        return vehicle.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/vin/{vin}")
    public ResponseEntity<VehicleEntity> updateVehicleByVIN(@PathVariable String vin, @RequestBody VehicleEntity updated) {
        Optional<VehicleEntity> existing = vehicleDao.findByVIN(vin);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        VehicleEntity vehicle = existing.get();
        vehicle.setMake(updated.getMake());
        vehicle.setModel(updated.getModel());
        vehicle.setYear(updated.getYear());
        vehicle.setVIN(vin);
        VehicleEntity saved = vehicleDao.save(vehicle);
        log.info("Updated vehicle with VIN {}", saved.getVIN());
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/vin/{vin}")
    public ResponseEntity<Void> deleteVehicleByVIN(@PathVariable String vin) {
        if (vehicleDao.findByVIN(vin).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        vehicleDao.deleteByVIN(vin);
        log.info("Deleted vehicle with VIN {}", vin);
        return ResponseEntity.noContent().build();
    }
}
