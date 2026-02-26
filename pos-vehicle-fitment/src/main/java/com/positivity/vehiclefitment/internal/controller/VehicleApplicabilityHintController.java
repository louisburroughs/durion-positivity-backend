package com.positivity.vehiclefitment.internal.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.events.EmitEvent;
import com.positivity.vehiclefitment.internal.dto.CreateHintRequest;
import com.positivity.vehiclefitment.internal.dto.FilterProductsResponse;
import com.positivity.vehiclefitment.internal.dto.HintResponse;
import com.positivity.vehiclefitment.internal.dto.UpdateHintRequest;
import com.positivity.vehiclefitment.service.VehicleApplicabilityHintService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller for managing vehicle applicability hints.
 */
@Slf4j
@Tag(name = "Vehicle Applicability Hints", description = "Endpoints for managing product fitment hints and filtering products by vehicle attributes")
@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/vehicle-fitment/hints")
public class VehicleApplicabilityHintController {

    private final VehicleApplicabilityHintService hintService;

    @Operation(summary = "Create a vehicle applicability hint", description = "Create a new hint with fitment tags for a product")
    @ApiResponse(responseCode = "201", description = "Hint created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request data")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @EmitEvent(id = "FITMENT_HINT_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<HintResponse> createHint(@Valid @RequestBody CreateHintRequest request) {
        try {
            HintResponse response = hintService.createHint(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.error("Error creating hint: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @Operation(summary = "Update a vehicle applicability hint", description = "Update the fitment tags for an existing hint")
    @ApiResponse(responseCode = "200", description = "Hint updated successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request data")
    @ApiResponse(responseCode = "404", description = "Hint not found")
    @EmitEvent(id = "FITMENT_HINT_UPDATE", apiVersion = "1")
    @PutMapping("/{hintId}")
    public ResponseEntity<HintResponse> updateHint(
            @Parameter(description = "ID of the hint to update") @PathVariable UUID hintId,
            @Valid @RequestBody UpdateHintRequest request) {
        try {
            HintResponse response = hintService.updateHint(hintId, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.error("Error updating hint {}: {}", hintId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @Operation(summary = "Delete a vehicle applicability hint", description = "Remove a hint and all its associated tags")
    @ApiResponse(responseCode = "204", description = "Hint deleted successfully")
    @ApiResponse(responseCode = "404", description = "Hint not found")
    @EmitEvent(id = "FITMENT_HINT_DELETE", apiVersion = "1")
    @DeleteMapping("/{hintId}")
    public ResponseEntity<Void> deleteHint(
            @Parameter(description = "ID of the hint to delete") @PathVariable UUID hintId) {
        try {
            hintService.deleteHint(hintId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.error("Error deleting hint {}: {}", hintId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @Operation(summary = "Get a vehicle applicability hint", description = "Retrieve a hint by its ID")
    @ApiResponse(responseCode = "200", description = "Hint retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Hint not found")
    @GetMapping("/{hintId}")
    public ResponseEntity<HintResponse> getHint(
            @Parameter(description = "ID of the hint to retrieve") @PathVariable UUID hintId) {
        try {
            HintResponse response = hintService.getHint(hintId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.error("Error retrieving hint {}: {}", hintId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @Operation(summary = "Get hints by product ID", description = "Retrieve all hints associated with a specific product")
    @ApiResponse(responseCode = "200", description = "Hints retrieved successfully")
    @GetMapping("/product/{productId}")
    public ResponseEntity<List<HintResponse>> getHintsByProductId(
            @Parameter(description = "ID of the product") @PathVariable UUID productId) {
        List<HintResponse> responses = hintService.getHintsByProductId(productId);
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "Filter products by vehicle attributes", description = "Find products that match the provided vehicle attributes")
    @ApiResponse(responseCode = "200", description = "Products filtered successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request data")
    @EmitEvent(id = "FITMENT_PRODUCTS_FILTER", apiVersion = "1")
    @PostMapping("/filter-products")
    public ResponseEntity<FilterProductsResponse> filterProducts(
            @RequestBody Map<String, String> vehicleAttributes) {
        FilterProductsResponse response = hintService.filterProductsByVehicleAttributes(vehicleAttributes);
        return ResponseEntity.ok(response);
    }
}
