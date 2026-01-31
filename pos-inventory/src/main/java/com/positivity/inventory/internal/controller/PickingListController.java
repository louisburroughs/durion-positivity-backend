package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/v1/inventory/pickingLists")
@Tag(name = "Picking Lists", description = "Picking list command endpoints")
public class PickingListController {

    @PostMapping("/{id}/confirm")
    @EmitEvent(id = "INVENTORY_PICKING_LIST_CONFIRM", apiVersion = "1")
    @Operation(summary = "Confirm picking list", description = "Confirms a picking list and commits consumption. Stub implementation.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Picking list confirmed"),
            @ApiResponse(responseCode = "501", description = "Not implemented")
    })
    public ResponseEntity<Void> confirmPickingList(
            @Parameter(description = "Picking list identifier", required = true) @PathVariable String id,
            @RequestBody(required = false) Object requestBody) {
        log.info("POST /v1/inventory/pickingLists/{}/confirm", id);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
