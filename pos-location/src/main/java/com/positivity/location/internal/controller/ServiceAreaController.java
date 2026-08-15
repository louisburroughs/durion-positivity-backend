package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.ServiceAreaRequest;
import com.positivity.location.internal.dto.ServiceAreaResponse;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.service.ServiceAreaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for service area management.
 *
 * Issue: #76
 */
@Tag(name = "Service Area API", description = "Operations for managing service areas")
@RestController
@RequestMapping("/v1/service-areas")
@RequiredArgsConstructor
public class ServiceAreaController {

    private static final String SERVICE_AREA_EXAMPLE = """
            {"name":"North Metro",
             "description":"Northern metropolitan coverage zone",
             "active":true,
             "postalCodes":[{"postalCode":"62704","countryCode":"US"},
                            {"postalCode":"62711","countryCode":"US"}]}
            """;

    private final ServiceAreaService serviceAreaService;

    @Operation(operationId = "createServiceArea", summary = "Create a Postal Code Service Area", description = """
                    Creates a service area, a named set of postal codes that defines where mobile coverage can be \
                    offered.
                    Use this tool before wiring coverage rules that reference the area; do not use \
                    patchServiceArea, which edits an existing area.
                    Preconditions: at least one postal code entry must be supplied and every entry must carry a \
                    countryCode; the name must not collide with an existing service area.
                    Required inputs: name and postalCodes, each entry with postalCode and countryCode; \
                    description is optional and active defaults to true.
                    Emits a LOCATION_SERVICE_AREA_CREATE event.
                    Returns 201 with the created area and 409 when the name is already taken.
                    """)
    @ApiResponse(responseCode = "201", description = "Service area created")
    @ApiResponse(responseCode = "409", description = "Service area name already taken")
    @EmitEvent(id = "LOCATION_SERVICE_AREA_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + LocationPermissions.SERVICE_AREA_MANAGE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:service-area:manage"})
    @PostMapping
    public ResponseEntity<ServiceAreaResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Service area to create, defined by its postal code and country code" + " entries.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Metro area", value = SERVICE_AREA_EXAMPLE)))
                    @RequestBody
                    ServiceAreaRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(serviceAreaService.create(request));
    }

    @Operation(operationId = "listServiceAreas", summary = "List All Configured Service Areas", description = """
                    Lists all configured service areas with their postal code sets and active flags.
                    Use this tool to discover area ids for coverage rules; use listCoverageRules instead to see \
                    which areas a specific mobile unit uses.
                    Preconditions: none beyond the location:service-area:read authority.
                    Required inputs: none; there are no parameters, no paging and no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the full unpaginated list.
                    """)
    @ApiResponse(responseCode = "200", description = "Service areas listed")
    @PreAuthorize("hasAuthority('" + LocationPermissions.SERVICE_AREA_READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:service-area:read"})
    @GetMapping
    public ResponseEntity<List<ServiceAreaResponse>> list() {
        return ResponseEntity.ok(serviceAreaService.list());
    }

    @Operation(operationId = "patchServiceArea", summary = "Patch Fields of a Service Area", description = """
                    Applies a partial update to a service area, accepting only the keys description and active.
                    Use this tool to retire an area with active=false or amend its description; do not use it to \
                    rename an area or change its postal codes, which are immutable after createServiceArea.
                    Preconditions: the service area must exist.
                    Required inputs: id (UUID) as a path parameter and a JSON object; keys other than description \
                    and active are silently ignored.
                    Emits a LOCATION_SERVICE_AREA_PATCH event.
                    Returns 400 when the id is not a valid UUID and 404 when no service area exists for it.
                    """)
    @ApiResponse(responseCode = "200", description = "Service area patched")
    @ApiResponse(responseCode = "400", description = "Invalid service area id")
    @ApiResponse(responseCode = "404", description = "Service area not found")
    @PreAuthorize("hasAuthority('" + LocationPermissions.SERVICE_AREA_MANAGE + "')")
    @EmitEvent(id = "LOCATION_SERVICE_AREA_PATCH", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:service-area:manage"})
    @PatchMapping("/{id}")
    public ResponseEntity<ServiceAreaResponse> patch(
            @PathVariable String id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Free-form patch object; only the keys description and active are" + " recognized.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Retire area",
                                                            value =
                                                                    "{\"description\":\"Retired winter zone\",\"active\":false}")))
                    @RequestBody
                    Map<String, Object> patch) {
        return ResponseEntity.ok(serviceAreaService.patch(id, patch));
    }
}
