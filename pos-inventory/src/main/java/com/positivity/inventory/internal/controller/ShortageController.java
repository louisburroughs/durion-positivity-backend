package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.dto.shortage.ShortageResolutionRequest;
import com.positivity.inventory.dto.shortage.ShortageResolutionResponse;
import com.positivity.inventory.service.ShortageResolutionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/allocations/shortages")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('inventory:shortages:resolve')")
public class ShortageController {

    private final ShortageResolutionService shortageResolutionService;

    @PostMapping("/resolve")
    @EmitEvent(id = "INVENTORY_SHORTAGE_RESOLVE", apiVersion = "1")
    public ResponseEntity<ShortageResolutionResponse> resolveShortage(
            @Valid @RequestBody ShortageResolutionRequest request) {
        return ResponseEntity.ok(shortageResolutionService.resolveShortage(request));
    }
}