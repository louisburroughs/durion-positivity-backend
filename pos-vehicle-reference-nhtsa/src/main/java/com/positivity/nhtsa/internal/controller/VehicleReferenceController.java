package com.positivity.nhtsa.internal.controller;

import com.positivity.nhtsa.internal.entity.Manufacturer;
import com.positivity.nhtsa.internal.entity.Make;
import com.positivity.nhtsa.internal.entity.Model;
import com.positivity.nhtsa.internal.entity.VehicleType;
import com.positivity.nhtsa.service.VehicleReferenceService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/vehicle-fitment")
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
    public List<Make> getMakesByManufacturer(@PathVariable UUID manufacturerId) {
        return vehicleReferenceService.getMakesByManufacturer(manufacturerId);
    }

    @GetMapping("/models/{makeId}")
    public List<Model> getModelsByMake(@PathVariable UUID makeId) {
        return vehicleReferenceService.getModelsByMake(makeId);
    }

    @GetMapping("/vehicle-types/{makeId}")
    public List<VehicleType> getVehicleTypesForMake(@PathVariable UUID makeId) {
        return vehicleReferenceService.getVehicleTypesForMake(makeId);
    }
}
