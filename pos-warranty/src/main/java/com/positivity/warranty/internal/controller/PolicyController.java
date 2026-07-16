package com.positivity.warranty.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.warranty.internal.dto.PolicyRequest;
import com.positivity.warranty.internal.dto.PolicyResponse;
import com.positivity.warranty.internal.enums.CoverageType;
import com.positivity.warranty.internal.security.WarrantyPermissions;
import com.positivity.warranty.internal.service.PolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

/** Warranty policy CRUD and applicable-policy lookup (PRD §3.2, §8 rows 2-3). */
@Tag(name = "Warranty Policies", description = "Structured coverage terms — one row per distinct program")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/warranty/policies")
public class PolicyController {

    private final PolicyService policyService;

    @Operation(summary = "List policies", description = "Optionally filter by provider and/or coverage type")
    @ApiResponse(responseCode = "200", description = "Policies returned.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.POLICY_VIEW + "')")
    @GetMapping
    public ResponseEntity<List<PolicyResponse>> listPolicies(
            @Parameter(description = "Filter by owning provider id") @RequestParam(required = false) UUID providerId,
            @Parameter(description = "Filter by coverage type") @RequestParam(required = false)
                    CoverageType coverageType) {
        return ResponseEntity.ok(policyService.list(providerId, coverageType));
    }

    @Operation(
            summary = "Find applicable policies",
            description = "Policies in effect on the sale date whose appliesTo scope matches any provided key,"
                    + " ordered most-specific-first (PRODUCT > MANUFACTURER > CATEGORY > ALL)")
    @ApiResponse(responseCode = "200", description = "Matching policies returned, most specific first.")
    @ApiResponse(responseCode = "400", description = "Missing or invalid parameters.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.POLICY_VIEW + "')")
    @GetMapping("/applicable")
    public ResponseEntity<List<PolicyResponse>> findApplicablePolicies(
            @Parameter(description = "pos-catalog productEntityId of the product sold") @RequestParam(required = false)
                    UUID productEntityId,
            @Parameter(description = "pos-catalog manufacturerId of the product sold") @RequestParam(required = false)
                    UUID manufacturerId,
            @Parameter(description = "pos-catalog category id of the product sold") @RequestParam(required = false)
                    UUID categoryId,
            @Parameter(description = "Original sale date (ISO-8601)", required = true)
                    @RequestParam
                    @NotNull
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate saleDate,
            @Parameter(description = "Restrict to one coverage type") @RequestParam(required = false)
                    CoverageType coverageType) {
        return ResponseEntity.ok(
                policyService.findApplicable(productEntityId, manufacturerId, categoryId, saleDate, coverageType));
    }

    @Operation(summary = "Get policy", description = "Retrieve a warranty policy by id")
    @ApiResponse(responseCode = "200", description = "Policy returned.")
    @ApiResponse(responseCode = "404", description = "Policy not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.POLICY_VIEW + "')")
    @GetMapping("/{id}")
    public ResponseEntity<PolicyResponse> getPolicy(
            @Parameter(description = "Policy UUID", required = true) @PathVariable @NotNull UUID id) {
        return ResponseEntity.ok(policyService.getById(id));
    }

    @Operation(summary = "Create policy", description = "Create a new warranty policy")
    @ApiResponse(responseCode = "201", description = "Policy created.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @ApiResponse(responseCode = "404", description = "Referenced provider not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.POLICY_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_POLICY_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<PolicyResponse> createPolicy(@Valid @NotNull @RequestBody PolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(policyService.create(request));
    }

    @Operation(summary = "Update policy", description = "Full update of an existing warranty policy")
    @ApiResponse(responseCode = "200", description = "Policy updated.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @ApiResponse(responseCode = "404", description = "Policy or referenced provider not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.POLICY_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_POLICY_UPDATE", apiVersion = "1")
    @PutMapping("/{id}")
    public ResponseEntity<PolicyResponse> updatePolicy(
            @Parameter(description = "Policy UUID", required = true) @PathVariable @NotNull UUID id,
            @Valid @NotNull @RequestBody PolicyRequest request) {
        return ResponseEntity.ok(policyService.update(id, request));
    }
}
