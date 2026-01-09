package com.durion.inventory.api;

import com.durion.inventory.api.dto.DeactivateLocationRequest;
import com.durion.inventory.api.dto.DeactivateLocationResponse;
import com.durion.inventory.service.InventoryLocationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/inventory/locations")
public class InventoryLocationDeactivationController {

    private final InventoryLocationService service;

    public InventoryLocationDeactivationController(InventoryLocationService service) {
        this.service = service;
    }

    @PostMapping("/{locationId}/deactivate")
    public ResponseEntity<DeactivateLocationResponse> deactivate(
            @PathVariable("locationId") UUID locationId,
            @RequestBody(required = false) DeactivateLocationRequest body
    ) {
        DeactivateLocationResponse resp = service.deactivateLocation(locationId,
                body != null ? body.getDestinationLocationId() : null);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(resp);
    }
}
