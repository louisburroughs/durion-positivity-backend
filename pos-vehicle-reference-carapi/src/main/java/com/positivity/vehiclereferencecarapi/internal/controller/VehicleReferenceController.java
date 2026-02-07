package com.positivity.vehiclereferencecarapi.internal.controller;

import com.positivity.vehiclereferencecarapi.internal.entity.CarApiMake;
import com.positivity.vehiclereferencecarapi.internal.entity.CarApiModel;
import com.positivity.vehiclereferencecarapi.service.VehicleReferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/vehicle-reference")
public class VehicleReferenceController {
    private final VehicleReferenceService vehicleReferenceService;

    @GetMapping("/makes")
    public List<CarApiMake> getMakes() {
        return vehicleReferenceService.getMakes();
    }

    @GetMapping("/models/{makeId}")
    public List<CarApiModel> getModelsByMakeId(@PathVariable UUID makeId) {
        return vehicleReferenceService.getModelsByMakeId(makeId);
    }
}
