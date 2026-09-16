package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.ServiceDto;
import com.positivity.catalog.internal.dto.ServiceRequirementsRequest;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.internal.service.ServiceRequirementService;
import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Declares the skills a catalog service requires, per vehicle GVWR class (CAP-329, spec §6.1). */
@RestController
@RequestMapping("/v1/products/services")
@RequiredArgsConstructor
@Tag(name = "Service Requirements", description = "Skill requirements a catalog service declares (CAP-329)")
public class ServiceRequirementController {

    private static final String SYSTEM = "system";

    private final ServiceRequirementService serviceRequirementService;

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.SERVICE_REQUIREMENT_MANAGE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.SERVICE_REQUIREMENT_MANAGE})
    @PutMapping("/{serviceId}/requirements")
    @EmitEvent(id = "CATALOG_SERVICE_REQUIREMENTS_SET", apiVersion = "1")
    @Operation(
            operationId = "setServiceRequirements",
            summary = "Declare the Skills a Service Requires",
            description = """
                    Replaces the whole skill requirement declaration of one catalog service, each required skill \
                    scoped to a GVWR class range (1-8) or to ANY class when both bounds are omitted.
                    Use this tool when a service needs competence that differs by vehicle class — the same brake \
                    job requiring A-series brakes on classes 1-3 and T-series on 4-8 — or to declare a service \
                    unconstrained by sending an empty list; do not create a second SKU for a competence-only \
                    difference, fork at the SKU only when labor time or price differs.
                    Preconditions: the service exists, and every skillId is an active row of the skill registry \
                    (read it at GET /v1/people/skills) whose own class range covers the range declared here.
                    Required inputs: serviceId (UUID) as a path parameter and a requiredSkills list, possibly \
                    empty, of {skillId, minGvwrClass, maxGvwrClass}.
                    Emits a CATALOG_SERVICE_REQUIREMENTS_SET audit event and a catalog.service.updated fact \
                    (schema v3) carrying requirementsConfiguredAt and requiredSkills, so schedulers resolve the \
                    requirement from their replica without calling back.
                    Returns 200 with the service, 404 when the service does not exist, and 422 with SKILL_UNKNOWN, \
                    SKILL_RETIRED, SKILL_DUPLICATE or SKILL_CLASS_RANGE_INVALID naming the offending value.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Requirements declared; the service with its requirement profile",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ServiceDto.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Service not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "A skill is unknown, retired, duplicated, or its class range is invalid",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ServiceDto> setServiceRequirements(
            @Parameter(description = "Catalog service id") @PathVariable @NonNull UUID serviceId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Replace-set list of required skills; empty declares the service unconstrained",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = ServiceRequirementsRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "Class-conditional brake competence",
                                                            value = """
                                    {"requiredSkills": [
                                      {"skillId": "01960011-0000-7000-8000-000000000040", "minGvwrClass": 1, "maxGvwrClass": 3},
                                      {"skillId": "01960011-0000-7000-8000-000000000041", "minGvwrClass": 4, "maxGvwrClass": 8}
                                    ]}""")))
                    @Valid
                    @RequestBody
                    @NonNull
                    ServiceRequirementsRequest request) {
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
        return ResponseEntity.ok(serviceRequirementService.setRequirements(serviceId, request, actor));
    }
}
