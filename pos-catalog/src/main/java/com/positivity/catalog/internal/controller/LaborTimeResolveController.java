package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.service.ServiceLaborTimeService;
import com.positivity.catalog.service.model.LaborTimeQuoteRequest;
import com.positivity.catalog.service.model.LaborTimeQuoteResponse;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ADR-0058 §5 REST edge for labor-time resolution (#1569 Phase 1). Thin by design: the
 * contract lives in {@code catalog.service.model}, the logic in the resolution service, and the
 * one approved caller is pos-workorder's {@code CatalogLaborTimeClientImpl} (ADR-0044 amendment
 * 2026-09-02, file-scoped in the platform {@code DomainWallsTest}).
 */
@Tag(
        name = "Labor Time Resolution",
        description = "Scoped service-to-service edge: resolve the applicable estimated labor time for a"
                + " service operation on a specific vehicle, with provenance and typed degradation.")
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/v1/catalog/labor-times")
public class LaborTimeResolveController {

    private final ServiceLaborTimeService serviceLaborTimeService;

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.LABOR_TIME_RESOLVE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.LABOR_TIME_RESOLVE})
    @PostMapping("/resolve")
    @EmitEvent(id = "CATALOG_LABOR_TIME_RESOLVE", apiVersion = "1")
    @Operation(
            operationId = "resolveLaborTime",
            summary = "Resolve the Labor Time for an Operation on a Vehicle",
            description = """
            Answers the one applicable estimated labor time for a catalog service operation on the described
            vehicle: stored vehicle-keyed standards first (most specific match wins, source precedence is
            policy data), then live QUERY_ONLY guides under a license-bounded cache, then the service's
            vehicle-agnostic default hours, each answer carrying source, revision and match grade.
            Use this tool from the approved pos-workorder client to prefill a LABOR estimate line; do not use
            it to browse what times exist, which is the labor-standards listing's job.
            Preconditions: the service must exist; unknown vehicle fields are sent null and widen the match.
            Required inputs: a body with serviceId; vehicle fields and preferredTimeType are optional.
            Emits a CATALOG_LABOR_TIME_RESOLVE event; no state changes.
            Returns 200 always for a well-formed request — a miss is the typed NO_TIME_AVAILABLE or
            SOURCE_UNAVAILABLE status, never an error, and callers degrade to a blank prefill.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "The resolved time with provenance, or a typed miss.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LaborTimeQuoteResponse.class)))
    public ResponseEntity<LaborTimeQuoteResponse> resolveLaborTime(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The operation being quoted, the vehicle on the lift (null fields"
                                    + " widen the match), and an optional time-class preference.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = LaborTimeQuoteRequest.class),
                                            examples =
                                                    @ExampleObject(name = "Front brake pads on a Civic", value = """
                                                            {"serviceId":"56b14899-cb6c-7628-0763-4c603ec0a325",
                                                             "vehicleYear":"2019-2023",
                                                             "make":"Honda","model":"Civic",
                                                             "preferredTimeType":"RETAIL_FLAT_RATE"}
                                                            """)))
                    @Valid
                    @RequestBody
                    LaborTimeQuoteRequest request) {
        return ResponseEntity.ok(serviceLaborTimeService.resolveLaborTime(request));
    }
}
