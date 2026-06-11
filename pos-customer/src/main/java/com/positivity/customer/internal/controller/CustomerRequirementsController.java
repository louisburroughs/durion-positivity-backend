package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.internal.service.CustomerRequirementsService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Customer Requirements", description = "Eligibility checks for customer-facing workflows")
@RestController
@RequiredArgsConstructor
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {CrmPermissionRegistry.PARTY_VIEW})
@RequestMapping("/v1/customers")
public class CustomerRequirementsController {

    private final CustomerRequirementsService customerRequirementsService;

    @Operation(
            summary = "Check whether a customer meets requirements",
            description = "Returns true when the customer is active and, for commercial parties, not on credit hold.")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Customer requirements evaluation returned",
                        content = @Content(schema = @Schema(implementation = Boolean.class))),
                @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
                @ApiResponse(
                        responseCode = "403",
                        description = "Caller lacks PARTY_VIEW authority",
                        content = @Content),
                @ApiResponse(responseCode = "404", description = "Customer not found", content = @Content)
            })
    @GetMapping("/{id}/requirements-met")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    @EmitEvent(id = "CUSTOMER_REQUIREMENTS_MET_GET", apiVersion = "1")
    public ResponseEntity<Boolean> requirementsMet(
            @Parameter(description = "Customer ID", required = true) @PathVariable @NonNull UUID id) {
        return customerRequirementsService
                .requirementsMet(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
