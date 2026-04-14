package com.positivity.nhtsa.internal.controller;

import com.positivity.nhtsa.internal.dto.MakeResponse;
import com.positivity.nhtsa.internal.dto.ManufacturerResponse;
import com.positivity.nhtsa.internal.dto.ModelResponse;
import com.positivity.nhtsa.internal.dto.VehicleReferenceMapper;
import com.positivity.nhtsa.internal.dto.VehicleTypeResponse;
import com.positivity.nhtsa.internal.service.VehicleReferenceService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/vehicle-fitment")
public class VehicleReferenceController {

    private final VehicleReferenceService vehicleReferenceService;

    public VehicleReferenceController(VehicleReferenceService vehicleReferenceService) {
        this.vehicleReferenceService = vehicleReferenceService;
    }

    @GetMapping("/manufacturers")
    public List<ManufacturerResponse> getManufacturers() {
        return vehicleReferenceService.getManufacturers().stream()
                .map(VehicleReferenceMapper::toManufacturerResponse)
                .toList();
    }

    @GetMapping("/makes/{manufacturerId}")
    public List<MakeResponse> getMakesByManufacturer(@PathVariable UUID manufacturerId) {
        return vehicleReferenceService.getMakesByManufacturer(manufacturerId).stream()
                .map(VehicleReferenceMapper::toMakeResponse)
                .toList();
    }

    @GetMapping("/models/{makeId}")
    public List<ModelResponse> getModelsByMake(@PathVariable UUID makeId) {
        return vehicleReferenceService.getModelsByMake(makeId).stream()
                .map(VehicleReferenceMapper::toModelResponse)
                .toList();
    }

    @GetMapping("/vehicle-types/{makeId}")
    public List<VehicleTypeResponse> getVehicleTypesForMake(@PathVariable UUID makeId) {
        return vehicleReferenceService.getVehicleTypesForMake(makeId).stream()
                .map(VehicleReferenceMapper::toVehicleTypeResponse)
                .toList();
    }
}
