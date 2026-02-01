package com.positivity.nhtsa.internal.controller;

import com.positivity.nhtsa.internal.entity.Manufacturer;
import com.positivity.nhtsa.internal.entity.Make;
import com.positivity.nhtsa.internal.entity.Model;
import com.positivity.nhtsa.internal.entity.VehicleType;
import com.positivity.nhtsa.service.VehicleReferenceService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/vehicle-fitment")
public class VehicleReferenceController {

    private final VehicleReferenceService vehicleReferenceService;

    public VehicleReferenceController(VehicleReferenceService vehicleReferenceService) {
        this.vehicleReferenceService = vehicleReferenceService;
    }

    @GetMapping("/manufacturers")
    public List<Manufacturer> getManufacturers() {
        return vehicleReferenceService.getManufacturers();
    }

    @GetMapping("/makes/{manufacturerId}")
    public List<Make> getMakesByManufacturer(@PathVariable Long manufacturerId) {
        return vehicleReferenceService.getMakesByManufacturer(manufacturerId);
    }

    @GetMapping("/models/{makeId}")
    public List<Model> getModelsByMake(@PathVariable Long makeId) {
        return vehicleReferenceService.getModelsByMake(makeId);
    }

    @GetMapping("/vehicle-types/{makeId}")
    public List<VehicleType> getVehicleTypesForMake(@PathVariable Long makeId) {
        return vehicleReferenceService.getVehicleTypesForMake(makeId);
    }
}
