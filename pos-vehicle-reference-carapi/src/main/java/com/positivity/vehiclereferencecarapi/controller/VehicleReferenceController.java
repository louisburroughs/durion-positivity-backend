package com.positivity.vehiclereferencecarapi.controller;

import com.positivity.vehiclereferencecarapi.entity.CarApiMake;
import com.positivity.vehiclereferencecarapi.entity.CarApiModel;
import com.positivity.vehiclereferencecarapi.service.VehicleReferenceService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/vehicle-reference")
public class VehicleReferenceController {
    private final VehicleReferenceService vehicleReferenceService;

    public VehicleReferenceController(VehicleReferenceService vehicleReferenceService) {
        this.vehicleReferenceService = vehicleReferenceService;
    }

    @GetMapping("/makes")
    public List<CarApiMake> getMakes() {
        return vehicleReferenceService.getMakes();
    }

    @GetMapping("/models/{makeId}")
    public List<CarApiModel> getModelsByMakeId(@PathVariable String makeId) {
        return vehicleReferenceService.getModelsByMakeId(makeId);
    }
}

