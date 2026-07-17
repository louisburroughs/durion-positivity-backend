package com.positivity.warranty.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.warranty.internal.dto.RegistrationRequest;
import com.positivity.warranty.internal.dto.RegistrationResponse;
import com.positivity.warranty.internal.enums.RegistrationStatus;
import com.positivity.warranty.internal.security.WarrantyPermissions;
import com.positivity.warranty.internal.service.RegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Sold-coverage (registration) CRUD and lookup (PRD §3.3, §8 row 4). */
@Tag(
        name = "Warranty Registrations",
        description = "Sold/instantiated coverage — road-hazard add-ons and extended plans")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/warranty/registrations")
public class RegistrationController {

    private final RegistrationService registrationService;

    @Operation(summary = "Search registrations", description = "Optionally filter by customer, vehicle, and status")
    @ApiResponse(responseCode = "200", description = "Registrations returned.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.REGISTRATION_VIEW + "')")
    @GetMapping
    public ResponseEntity<List<RegistrationResponse>> searchRegistrations(
            @Parameter(description = "Filter by customer id") @RequestParam(required = false) UUID customerId,
            @Parameter(description = "Filter by vehicle id") @RequestParam(required = false) UUID vehicleId,
            @Parameter(description = "Filter by lifecycle status") @RequestParam(required = false)
                    RegistrationStatus status) {
        return ResponseEntity.ok(registrationService.search(customerId, vehicleId, status));
    }

    @Operation(summary = "Get registration", description = "Retrieve a warranty registration by id")
    @ApiResponse(responseCode = "200", description = "Registration returned.")
    @ApiResponse(responseCode = "404", description = "Registration not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.REGISTRATION_VIEW + "')")
    @GetMapping("/{id}")
    public ResponseEntity<RegistrationResponse> getRegistration(
            @Parameter(description = "Registration UUID", required = true) @PathVariable @NotNull UUID id) {
        return ResponseEntity.ok(registrationService.getById(id));
    }

    @Operation(summary = "Create registration", description = "Record sold/instantiated coverage")
    @ApiResponse(responseCode = "201", description = "Registration created.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @ApiResponse(responseCode = "404", description = "Referenced policy not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.REGISTRATION_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_REGISTRATION_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<RegistrationResponse> createRegistration(
            @Valid @NotNull @RequestBody RegistrationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registrationService.create(request));
    }

    @Operation(summary = "Update registration", description = "Full update of an existing warranty registration")
    @ApiResponse(responseCode = "200", description = "Registration updated.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @ApiResponse(responseCode = "404", description = "Registration or referenced policy not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.REGISTRATION_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_REGISTRATION_UPDATE", apiVersion = "1")
    @PutMapping("/{id}")
    public ResponseEntity<RegistrationResponse> updateRegistration(
            @Parameter(description = "Registration UUID", required = true) @PathVariable @NotNull UUID id,
            @Valid @NotNull @RequestBody RegistrationRequest request) {
        return ResponseEntity.ok(registrationService.update(id, request));
    }
}
