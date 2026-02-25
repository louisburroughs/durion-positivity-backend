package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.consumption.ConsumeItemsRequest;
import com.positivity.inventory.internal.dto.consumption.ConsumptionResponse;
import com.positivity.inventory.service.ConsumptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/consumption")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('inventory:availability:read','inventory:adjustment:create')")
public class ConsumptionController {

    private final ConsumptionService consumptionService;

    @PostMapping
    @EmitEvent(id = "INVENTORY_WORKORDER_CONSUMPTION_CREATE", apiVersion = "1")
    public ResponseEntity<ConsumptionResponse> consumePickedItems(@Valid @RequestBody ConsumeItemsRequest request) {
        ConsumptionResponse response = consumptionService.consumePickedItems(request);
        return ResponseEntity.status(201).body(response);
    }
}
