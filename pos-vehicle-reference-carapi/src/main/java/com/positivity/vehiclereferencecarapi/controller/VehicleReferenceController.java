package com.positivity.vehiclereferencecarapi.controller;

import com.positivity.vehiclereferencecarapi.entity.CarApiMake;
import com.positivity.vehiclereferencecarapi.entity.CarApiModel;
import com.positivity.vehiclereferencecarapi.service.VehicleReferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/vehicle-reference")
public class VehicleReferenceController {
    private final VehicleReferenceService vehicleReferenceService;

    @GetMapping("/makes")
    public List<CarApiMake> getMakes() {
        return vehicleReferenceService.getMakes();
    }

    @GetMapping("/models/{makeId}")
    public List<CarApiModel> getModelsByMakeId(@PathVariable String makeId) {
        return vehicleReferenceService.getModelsByMakeId(makeId);
    }
}

