package com.positivity.vehiclefitment.controller;

import com.positivity.vehiclefitment.entity.Manufacturer;
import com.positivity.vehiclefitment.entity.Make;
import com.positivity.vehiclefitment.entity.Model;
import com.positivity.vehiclefitment.entity.VehicleType;
import com.positivity.vehiclefitment.service.VehicleFitmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/vehicle-fitment")
public class VehicleFitmentController {

    private final VehicleFitmentService vehicleFitmentService;

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
