package com.positivity.location.internal.controller;

import com.positivity.location.internal.dto.BayTypeResponse;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.BayTypeService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only catalog of bay types and the specialty services each defaults a bay to (#2247).
 */
@Tag(name = "Bay Type API", description = "Bay type classifications and their default specialty services")
@RestController
@RequestMapping("/v1/bay-types")
public class BayTypeController {

    private final BayTypeService bayTypeService;

    public BayTypeController(BayTypeService bayTypeService) {
        this.bayTypeService = bayTypeService;
    }

    @Operation(
            operationId = "listBayTypes",
            summary = "List Bay Types with Default Specialty Services",
            description = """
                    Lists every bay type, in a fixed order, with whether it accepts general work and the \
                    specialty services a bay of that type is given by default: on createBay without \
                    serviceCapabilityCodes, and on patchBay when bayType changes without \
                    serviceCapabilityCodes in the same patch.
                    Use this tool to pre-fill a new bay's specialty services or to preview which specialties \
                    a bay type change will add or remove; use listBays or getBay instead to see the codes a \
                    particular bay holds now.
                    Preconditions: none beyond the location:bay:read authority; the defaults are the caller's \
                    tenant's specialty map, not any one location's.
                    Required inputs: none; there are no parameters, no paging and no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with one entry per bay type; a type with no defaults, or a tenant whose map was \
                    never provisioned, carries empty defaultServiceCapabilityCodes and defaultServices. Codes \
                    are sorted ascending and each defaultServices entry carries the catalog service name. A \
                    default naming a code that is not an active catalog service is left out here and logged as \
                    a seed defect; createBay without serviceCapabilityCodes still refuses with 422 while the \
                    map names such a code, so send an explicit list in that case.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Bay types listed",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = BayTypeResponse.class))))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks location:bay:read",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + LocationPermissions.BAY_READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:bay:read"})
    @GetMapping
    public ResponseEntity<List<BayTypeResponse>> listBayTypes() {
        return ResponseEntity.ok(bayTypeService.listBayTypes());
    }
}
