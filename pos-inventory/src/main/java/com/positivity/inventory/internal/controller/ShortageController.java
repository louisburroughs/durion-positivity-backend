package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.InventoryErrorResponse;
import com.positivity.inventory.internal.dto.shortage.ShortageResolutionRequest;
import com.positivity.inventory.internal.dto.shortage.ShortageResolutionResponse;
import com.positivity.inventory.service.ShortageResolutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Shortage Resolution", description = "Shortage resolution recommendation endpoints")
public class ShortageController {

    private final ShortageResolutionService shortageResolutionService;

    @PostMapping("/resolve")
    @EmitEvent(id = "INVENTORY_SHORTAGE_RESOLVE", apiVersion = "1")
    @Operation(
            summary = "Resolve shortage",
            description = "Returns candidate shortage resolution options from substitutes and external availability")
    @ApiResponse(responseCode = "200", description = "Resolution options returned", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ShortageResolutionResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failure", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "User lacks required shortage resolution authority", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "Unable to resolve shortage under current constraints", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryErrorResponse.class)))
    public ResponseEntity<ShortageResolutionResponse> resolveShortage(
            @Valid @RequestBody ShortageResolutionRequest request) {
        return ResponseEntity.ok(shortageResolutionService.resolveShortage(request));
    }
}
