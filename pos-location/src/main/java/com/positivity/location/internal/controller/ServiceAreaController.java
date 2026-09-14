package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.ServiceAreaPostalCodesRequest;
import com.positivity.location.internal.dto.ServiceAreaRequest;
import com.positivity.location.internal.dto.ServiceAreaResponse;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.ServiceAreaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PutMapping;
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

    private static final String POSTAL_CODES_EXAMPLE = """
            {"postalCodes":[{"postalCode":"62704","countryCode":"US"},
                            {"postalCode":"62711","countryCode":"US"},
                            {"postalCode":"62702","countryCode":"US"}]}
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
    @ApiResponse(responseCode = "400", description = "Empty postal code set, or an entry missing its countryCode")
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
                    @Valid
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
                    Use this tool to retire an area with active=false or amend its description; use \
                    replaceServiceAreaPostalCodes to change which postal codes it covers, which this tool \
                    cannot do. An area cannot be renamed.
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

    @Operation(
            operationId = "replaceServiceAreaPostalCodes",
            summary = "Replace the Postal Codes a Service Area Covers",
            description = """
                    Replaces the whole postal code set of a service area, so the area afterwards covers exactly \
                    the codes supplied and nothing else.
                    Use this tool whenever coverage changes — a market expands, a rural route is dropped, or an \
                    area was created with the wrong codes; patchServiceArea cannot touch postal codes and there \
                    is no way to delete an area and start again.
                    Preconditions: the service area must exist; at least one postal code entry must be supplied \
                    and every entry must carry a countryCode. Sending an empty set is refused rather than \
                    treated as "covers nothing" — retire an area with patchServiceArea active=false instead.
                    Required inputs: id (UUID) as a path parameter and a body of the form \
                    {"postalCodes": [...]}, each entry carrying postalCode and countryCode.
                    Emits a LOCATION_SERVICE_AREA_POSTAL_CODES_REPLACE event.
                    Returns 200 with the area as it stands afterwards, 400 when the id is not a valid UUID or \
                    the set is empty or missing a countryCode, and 404 when no service area exists for the id.
                    Coverage resolution reads these rows directly: findEligibleMobileUnits matches an address \
                    through them, so removing a code stops every mobile unit covering that address.
                    """)
    @ApiResponse(responseCode = "200", description = "Postal codes replaced")
    @ApiResponse(responseCode = "400", description = "Invalid service area id, or an empty or incomplete set")
    @ApiResponse(responseCode = "404", description = "Service area not found")
    @PreAuthorize("hasAuthority('" + LocationPermissions.SERVICE_AREA_MANAGE + "')")
    @EmitEvent(id = "LOCATION_SERVICE_AREA_POSTAL_CODES_REPLACE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:service-area:manage"})
    @PutMapping("/{id}/postal-codes")
    public ResponseEntity<ServiceAreaResponse> replacePostalCodes(
            @PathVariable String id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The complete postal code set the area should cover afterwards;"
                                    + " any code absent here stops being covered.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Expanded metro coverage",
                                                            value = POSTAL_CODES_EXAMPLE)))
                    @Valid
                    @RequestBody
                    ServiceAreaPostalCodesRequest request) {
        return ResponseEntity.ok(serviceAreaService.replacePostalCodes(id, request));
    }
}
