package com.positivity.vehiclereferencecarapi.internal.controller;

import com.positivity.vehiclereferencecarapi.internal.dto.CarApiMakeResponse;
import com.positivity.vehiclereferencecarapi.internal.dto.CarApiModelResponse;
import com.positivity.vehiclereferencecarapi.internal.dto.VehicleReferenceMapper;
import com.positivity.vehiclereferencecarapi.internal.service.VehicleReferenceService;
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
    public List<CarApiMakeResponse> getMakes() {
        return vehicleReferenceService.getMakes().stream()
                .map(VehicleReferenceMapper::toMakeResponse)
                .toList();
    }

    @GetMapping("/models/{makeId}")
    public List<CarApiModelResponse> getModelsByMakeId(@PathVariable UUID makeId) {
        return vehicleReferenceService.getModelsByMakeId(makeId).stream()
                .map(VehicleReferenceMapper::toModelResponse)
                .toList();
    }
}
