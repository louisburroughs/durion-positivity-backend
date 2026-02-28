package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.reallocation.ReallocateRequest;
import com.positivity.inventory.internal.dto.reallocation.ReallocateResponse;
import com.positivity.inventory.service.AllocationReallocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/allocations")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('inventory:allocations:reallocate')")
public class ReallocationController {

    private final AllocationReallocationService allocationReallocationService;

    @PostMapping("/reallocate")
    @EmitEvent(id = "INVENTORY_ALLOCATION_REALLOCATE", apiVersion = "1")
    public ResponseEntity<ReallocateResponse> reallocate(@Valid @RequestBody ReallocateRequest request) {
        return ResponseEntity.ok(allocationReallocationService.reallocate(request));
    }
}
