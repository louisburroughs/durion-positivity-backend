package com.positivity.warranty.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.warranty.internal.dto.ProviderRequest;
import com.positivity.warranty.internal.dto.ProviderResponse;
import com.positivity.warranty.internal.enums.ProviderType;
import com.positivity.warranty.internal.security.WarrantyPermissions;
import com.positivity.warranty.internal.service.ProviderService;
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

/** Warranty provider CRUD (PRD §3.1, §8 row 1). */
@Tag(name = "Warranty Providers", description = "Who pays — manufacturers, program administrators, the dealer itself")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/warranty/providers")
public class ProviderController {

    private final ProviderService providerService;

    @Operation(summary = "List providers", description = "Optionally filter by status and/or provider type")
    @ApiResponse(responseCode = "200", description = "Providers returned.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.PROVIDER_VIEW + "')")
    @GetMapping
    public ResponseEntity<List<ProviderResponse>> listProviders(
            @Parameter(description = "Filter by lifecycle status (ACTIVE/INACTIVE)") @RequestParam(required = false)
                    String status,
            @Parameter(description = "Filter by provider type") @RequestParam(required = false)
                    ProviderType providerType) {
        return ResponseEntity.ok(providerService.list(status, providerType));
    }

    @Operation(summary = "Get provider", description = "Retrieve a warranty provider by id")
    @ApiResponse(responseCode = "200", description = "Provider returned.")
    @ApiResponse(responseCode = "404", description = "Provider not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.PROVIDER_VIEW + "')")
    @GetMapping("/{id}")
    public ResponseEntity<ProviderResponse> getProvider(
            @Parameter(description = "Provider UUID", required = true) @PathVariable @NotNull UUID id) {
        return ResponseEntity.ok(providerService.getById(id));
    }

    @Operation(summary = "Create provider", description = "Create a new warranty provider")
    @ApiResponse(responseCode = "201", description = "Provider created.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.PROVIDER_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_PROVIDER_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<ProviderResponse> createProvider(@Valid @NotNull @RequestBody ProviderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(providerService.create(request));
    }

    @Operation(summary = "Update provider", description = "Full update of an existing warranty provider")
    @ApiResponse(responseCode = "200", description = "Provider updated.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @ApiResponse(responseCode = "404", description = "Provider not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.PROVIDER_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_PROVIDER_UPDATE", apiVersion = "1")
    @PutMapping("/{id}")
    public ResponseEntity<ProviderResponse> updateProvider(
            @Parameter(description = "Provider UUID", required = true) @PathVariable @NotNull UUID id,
            @Valid @NotNull @RequestBody ProviderRequest request) {
        return ResponseEntity.ok(providerService.update(id, request));
    }
}
