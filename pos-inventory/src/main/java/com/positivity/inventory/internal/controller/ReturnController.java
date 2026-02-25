package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.returns.ReturnItemsRequest;
import com.positivity.inventory.internal.dto.returns.ReturnResponse;
import com.positivity.inventory.service.ReturnService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/returns")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('inventory:availability:read','inventory:adjustment:create')")
public class ReturnController {

    private final ReturnService returnService;

    @PostMapping
    @EmitEvent(id = "INVENTORY_RETURN_TO_STOCK_CREATE", apiVersion = "1")
    public ResponseEntity<ReturnResponse> returnItemsToStock(@Valid @RequestBody ReturnItemsRequest request) {
        ReturnResponse response = returnService.returnItemsToStock(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
