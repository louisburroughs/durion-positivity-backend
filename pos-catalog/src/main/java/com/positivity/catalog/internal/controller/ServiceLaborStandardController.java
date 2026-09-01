package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.ServiceLaborStandardRequestDto;
import com.positivity.catalog.internal.dto.ServiceLaborStandardResponseDto;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.internal.service.ServiceLaborStandardService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authoring surface for hand-authored labor standards (#1569, sourcing plan §4.4).
 *
 * <p>Every row written here carries {@code sourceCode=DURION}: this is the human write path for
 * book times — dealer-created operations, shop corrections — beside the labor-guide import path
 * that arrives in a later phase. Corrections supersede rather than update, so a quote made
 * against an old row stays explainable.
 */
@Tag(
        name = "Service Labor Standards",
        description = "Vehicle-keyed estimated service times (book time) with provenance, per service"
                + " operation. Hand-authored DURION rows only; imported guide rows are read-only here.")
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/v1/catalog-items/service/{serviceId}/labor-standards")
public class ServiceLaborStandardController {

    private final ServiceLaborStandardService laborStandardService;

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.LABOR_STANDARD_MANAGE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.LABOR_STANDARD_MANAGE})
    @PostMapping
    @EmitEvent(id = "CATALOG_LABOR_STANDARD_CREATE", apiVersion = "1")
    @Operation(
            operationId = "createServiceLaborStandard",
            summary = "Author a Labor Standard for a Service",
            description = """
            Creates an active DURION-source labor standard: the estimated (book) time for this service
            operation on the vehicles the key fields describe, in decimal hours in tenths (0.1 hr = 6 min).
            Use this tool for hand-authored times — dealer-created operations or shop-decided standards; do not
            use it to transcribe a licensed labor guide, which enters through the guide import path with its own
            provenance.
            Preconditions: the service must exist, and no active standard may already cover the same vehicle key
            and time type — correct a wrong number by superseding it, not by adding a duplicate.
            Required inputs: serviceId path parameter and a body with laborHours; vehicle-key fields left null
            are wildcards, and timeType defaults to DURION_STANDARD.
            Emits a CATALOG_LABOR_STANDARD_CREATE event.
            Returns 400 for malformed hours (not in tenths, non-positive) or codes, 404 for an unknown service,
            and 409 when an active row already covers the same vehicle key and time type.
            """)
    @ApiResponse(
            responseCode = "201",
            description = "Labor standard created.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ServiceLaborStandardResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Malformed hours, time type, or operation codes")
    @ApiResponse(responseCode = "404", description = "Service does not exist")
    @ApiResponse(responseCode = "409", description = "An active standard already covers this vehicle key and type")
    public ResponseEntity<ServiceLaborStandardResponseDto> createLaborStandard(
            @Parameter(description = "Service the standard belongs to.", required = true) @PathVariable UUID serviceId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The standard to author: laborHours (decimal hours in tenths) plus"
                                    + " optional vehicle-key fields (null = any vehicle), timeType, overlap"
                                    + " metadata and published date.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = ServiceLaborStandardRequestDto.class),
                                            examples =
                                                    @ExampleObject(name = "Front brake pads on a Civic", value = """
                                                            {"vehicleYear":"2019-2023",
                                                             "make":"Honda","model":"Civic",
                                                             "laborHours":1.5,
                                                             "timeType":"DURION_STANDARD",
                                                             "overlapGroup":"WHEEL-OFF",
                                                             "publishedAt":"2026-09-01"}
                                                            """)))
                    @Valid
                    @RequestBody
                    ServiceLaborStandardRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(laborStandardService.create(serviceId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.LABOR_STANDARD_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.LABOR_STANDARD_VIEW})
    @GetMapping
    @EmitEvent(id = "CATALOG_LABOR_STANDARD_LIST", apiVersion = "1")
    @Operation(
            operationId = "listServiceLaborStandards",
            summary = "List a Service's Labor Standards",
            description = """
            Returns the service's labor standards — vehicle-keyed book times with source and revision — active
            rows only by default, oldest first.
            Use this tool to see what estimated times exist for an operation and where each came from; do not
            use it to resolve the one applicable time for a specific vehicle, which is the resolution endpoint's
            job once it exists (sourcing plan §3.4).
            Preconditions: the service must exist.
            Required inputs: serviceId path parameter; includeSuperseded=true adds replaced rows for audit.
            Emits a CATALOG_LABOR_STANDARD_LIST event; no state changes.
            Returns 404 when the service does not exist.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "The service's labor standards.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ServiceLaborStandardResponseDto.class)))
    @ApiResponse(responseCode = "404", description = "Service does not exist")
    public ResponseEntity<List<ServiceLaborStandardResponseDto>> listLaborStandards(
            @Parameter(description = "Service whose standards to list.", required = true) @PathVariable UUID serviceId,
            @Parameter(description = "Include superseded rows for audit history.") @RequestParam(defaultValue = "false")
                    boolean includeSuperseded) {
        return ResponseEntity.ok(laborStandardService.list(serviceId, includeSuperseded));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.LABOR_STANDARD_MANAGE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.LABOR_STANDARD_MANAGE})
    @PostMapping("/{standardId}/supersede")
    @EmitEvent(id = "CATALOG_LABOR_STANDARD_SUPERSEDE", apiVersion = "1")
    @Operation(
            operationId = "supersedeServiceLaborStandard",
            summary = "Supersede a Labor Standard with a Correction",
            description = """
            Replaces an active DURION-source standard: the old row is marked superseded and kept for audit, and
            the body becomes the new active row, returned with fresh provenance.
            Use this tool to correct a hand-authored time; do not use it on imported guide rows, which are
            corrected by their source's next import — the API refuses them.
            Preconditions: the standard must belong to the service, still be active, and carry source DURION.
            Required inputs: serviceId and standardId path parameters, plus a full replacement body — this is a
            replacement, not a patch, so omitted vehicle-key fields become wildcards.
            Emits a CATALOG_LABOR_STANDARD_SUPERSEDE event.
            Returns 400 for a malformed body, 404 when the standard is not this service's, and 409 when the row
            is already superseded or not DURION-source.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "The replacement row now active.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ServiceLaborStandardResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Malformed hours, time type, or operation codes")
    @ApiResponse(responseCode = "404", description = "Standard not found for this service")
    @ApiResponse(responseCode = "409", description = "Row already superseded, or not a DURION-source row")
    public ResponseEntity<ServiceLaborStandardResponseDto> supersedeLaborStandard(
            @Parameter(description = "Service the standard belongs to.", required = true) @PathVariable UUID serviceId,
            @Parameter(description = "Standard being replaced.", required = true) @PathVariable UUID standardId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The full replacement row — not a patch: omitted vehicle-key fields"
                                    + " become wildcards. Same shape as create.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = ServiceLaborStandardRequestDto.class),
                                            examples = @ExampleObject(name = "Corrected hours", value = """
                                                            {"vehicleYear":"2019-2023",
                                                             "make":"Honda","model":"Civic",
                                                             "laborHours":1.8,
                                                             "timeType":"DURION_STANDARD",
                                                             "overlapGroup":"WHEEL-OFF",
                                                             "publishedAt":"2026-09-01"}
                                                            """)))
                    @Valid
                    @RequestBody
                    ServiceLaborStandardRequestDto request) {
        return ResponseEntity.ok(laborStandardService.supersede(serviceId, standardId, request));
    }
}
