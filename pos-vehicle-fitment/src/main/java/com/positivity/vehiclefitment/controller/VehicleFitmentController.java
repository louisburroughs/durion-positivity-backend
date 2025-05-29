package com.positivity.vehiclefitment.controller;

import com.positivity.vehiclefitment.entity.Manufacturer;
import com.positivity.vehiclefitment.entity.Make;
import com.positivity.vehiclefitment.entity.Model;
import com.positivity.vehiclefitment.entity.VehicleType;
import com.positivity.vehiclefitment.service.VehicleFitmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/vehicle-fitment")
public class VehicleFitmentController {

    private VehicleFitmentService vehicleFitmentService;

    @Autowired
    public VehicleFitmentController(VehicleFitmentService vehicleFitmentService) {
        this.vehicleFitmentService = vehicleFitmentService;
    }

    @GetMapping("/manufacturers")
    public List<Manufacturer> getManufacturers() {
        return vehicleFitmentService.getManufacturers();
    }

    @GetMapping("/makes/{manufacturerId}")
    public List<Make> getMakesByManufacturer(@PathVariable Long manufacturerId) {
        return vehicleFitmentService.getMakesByManufacturer(manufacturerId);
    }

    @GetMapping("/models/{makeId}")
    public List<Model> getModelsByMake(@PathVariable Long makeId) {
        return vehicleFitmentService.getModelsByMake(makeId);
    }

    @GetMapping("/vehicle-types/{makeId}")
    public List<VehicleType> getVehicleTypesForMake(@PathVariable Long makeId) {
        return vehicleFitmentService.getVehicleTypesForMake(makeId);
    }
}
