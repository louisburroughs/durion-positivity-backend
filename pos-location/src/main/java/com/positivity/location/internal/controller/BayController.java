package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.BayPatchRequest;
import com.positivity.location.internal.dto.BayRequest;
import com.positivity.location.internal.dto.BayResponse;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.BayService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Tag(name = "Bay API", description = "Operations for managing bays within locations")
@RestController
@RequestMapping("/v1/locations/{locationId}/bays")
public class BayController {

    private static final String BAY_EXAMPLE = """
            {"name":"Bay A1",
             "bayType":"GENERAL_SERVICE",
             "capacity":{"maxConcurrentVehicles":2},
             "serviceCapabilityIds":["ALIGNMENT"],
             "skillRequirementIds":["018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a20"],
             "status":"ACTIVE"}
            """;

    /**
     * Documented on every operation that gates on the caller's location scope (ADR-0061, #1872).
     * The body is the {@code ApiError} envelope rendered by pos-security-common's
     * highest-precedence advice, not this module's ProblemDetail.
     */
    static final String BAY_READ_SCOPE_DENIED_DESCRIPTION =
            "Caller lacks location:bay:read, or holds it but its location scope does not cover locationId"
                    + " (ApiError.code LOCATION_SCOPE_DENIED, see docs/ERROR_ENVELOPE.md).";

    static final String BAY_MANAGE_SCOPE_DENIED_DESCRIPTION =
            "Caller lacks location:bay:manage, or holds it but its location scope does not cover locationId"
                    + " (ApiError.code LOCATION_SCOPE_DENIED, see docs/ERROR_ENVELOPE.md).";

    private final BayService bayService;

    public BayController(BayService bayService) {
        this.bayService = bayService;
    }

    @Operation(operationId = "listBays", summary = "List Service Bays of a Location", description = """
                    Lists the service bays of a location as a page, optionally filtered by status and bayType.
                    Use this tool to see bay capacity and status for a shop; use getBay instead when the bay id \
                    is already known.
                    Preconditions: the location must exist.
                    Required inputs: locationId (UUID) as a path parameter; status (ACTIVE or OUT_OF_SERVICE) and \
                    bayType filters are optional, and page defaults to 0 with size 20.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 400 when locationId does not parse as a UUID, 403 LOCATION_SCOPE_DENIED when a \
                    location-scoped location:bay:read grant does not cover locationId (ADR-0061), and 404 when \
                    the location does not exist; an unrecognized status or bayType filter value fails the \
                    request rather than returning an empty page.
                    """)
    @ApiResponse(responseCode = "200", description = "Bays retrieved successfully.")
    @ApiResponse(responseCode = "400", description = "locationId is not a UUID.")
    @ApiResponse(
            responseCode = "403",
            description = BAY_READ_SCOPE_DENIED_DESCRIPTION,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Location not found.")
    @PreAuthorize("hasAuthority('" + LocationPermissions.BAY_READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:bay:read"})
    @GetMapping
    public ResponseEntity<Page<BayResponse>> listBays(
            @Parameter(description = "Location ID") @PathVariable String locationId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String bayType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID location = requireInScope(locationId, LocationPermissions.BAY_READ);
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(bayService.listBays(location, status, bayType, pageable));
    }

    @Operation(operationId = "getBay", summary = "Get a Service Bay by Identifier", description = """
                    Returns a single service bay of a location, including its capacity, capability and skill \
                    requirement details.
                    Use this tool when both the location id and bay id are known; use listBays instead to search \
                    or enumerate.
                    Preconditions: the location must exist and the bay must belong to it.
                    Required inputs: locationId and bayId (UUIDs) as path parameters.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 400 when either id does not parse as a UUID, 403 LOCATION_SCOPE_DENIED when a \
                    location-scoped location:bay:read grant does not cover locationId (ADR-0061), and 404 when \
                    the location does not exist or the bay is not found under that location.
                    """)
    @ApiResponse(responseCode = "200", description = "Bay retrieved successfully.")
    @ApiResponse(responseCode = "400", description = "locationId or bayId is not a UUID.")
    @ApiResponse(
            responseCode = "403",
            description = BAY_READ_SCOPE_DENIED_DESCRIPTION,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Bay not found.")
    @PreAuthorize("hasAuthority('" + LocationPermissions.BAY_READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:bay:read"})
    @GetMapping("/{bayId}")
    public ResponseEntity<BayResponse> getBay(
            @Parameter(description = "Location ID") @PathVariable String locationId,
            @Parameter(description = "Bay ID") @PathVariable String bayId) {
        UUID location = requireInScope(locationId, LocationPermissions.BAY_READ);
        return ResponseEntity.ok(bayService.getBay(location, parseUuid(bayId)));
    }

    @Operation(operationId = "createBay", summary = "Create a Service Bay for Location", description = """
                    Creates a service bay under a location with a type classification, concurrency capacity and \
                    optional capability and skill requirements.
                    Use this tool when adding physical work capacity to a shop; do not use patchBay, which \
                    modifies a bay that already exists, and use createStorageLocation for inventory storage \
                    rather than vehicle bays.
                    Preconditions: the location must exist, no bay of that location may already use the name \
                    (case-insensitive), and any serviceCapabilityIds must match registered service capability \
                    codes.
                    Required inputs: name, bayType (one of GENERAL_SERVICE, ALIGNMENT, TIRE_SERVICE, HEAVY_DUTY, \
                    INSPECTION or WASH_DETAIL) and capacity.maxConcurrentVehicles of at least 1; status is \
                    optional, defaults to ACTIVE and only also accepts OUT_OF_SERVICE.
                    Emits a LOCATION_BAY_CREATE event; no other records are touched.
                    Returns 400 when locationId does not parse as a UUID, 403 LOCATION_SCOPE_DENIED when a \
                    location-scoped location:bay:manage grant does not cover locationId (ADR-0061), 404 when \
                    the location does not exist and 409 when the bay name is already taken at that location.
                    """)
    @ApiResponse(responseCode = "201", description = "Bay created successfully.")
    @ApiResponse(responseCode = "400", description = "locationId is not a UUID, or the payload is invalid.")
    @ApiResponse(
            responseCode = "403",
            description = BAY_MANAGE_SCOPE_DENIED_DESCRIPTION,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Location not found.")
    @ApiResponse(responseCode = "409", description = "Bay name already taken at this location.")
    @EmitEvent(id = "LOCATION_BAY_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + LocationPermissions.BAY_MANAGE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:bay:manage"})
    @PostMapping
    public ResponseEntity<BayResponse> createBay(
            @Parameter(description = "Location ID", example = "018e1c9f-6b5a-7890-abcd-1234567890ab") @PathVariable
                    String locationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Service bay to create, with its type classification and concurrent"
                                    + " vehicle capacity.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "General service bay", value = BAY_EXAMPLE)))
                    @Valid
                    @RequestBody
                    BayRequest request) {
        UUID location = requireInScope(locationId, LocationPermissions.BAY_MANAGE);
        BayResponse created = bayService.createBay(location, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(operationId = "patchBay", summary = "Patch Fields of a Service Bay", description = """
                    Applies a partial update to a bay, changing only the supplied fields: name, bayType, status, \
                    capacity and the capability or skill requirement lists.
                    Use this tool for status transitions between ACTIVE and OUT_OF_SERVICE and for capacity \
                    changes; do not use createBay, which adds a new bay.
                    Preconditions: the location must exist, the bay must belong to it, and a new name must not \
                    collide with another bay at the same location.
                    Required inputs: locationId and bayId (UUIDs) as path parameters and a body with at least one \
                    field; capacity.maxConcurrentVehicles, when supplied, must be at least 1.
                    Emits a LOCATION_BAY_UPDATE event; no other records are touched.
                    Returns 400 when either id does not parse as a UUID, 403 LOCATION_SCOPE_DENIED when a \
                    location-scoped location:bay:manage grant does not cover locationId (ADR-0061), 404 when \
                    the location or bay does not exist and 409 when the new name is already taken at that \
                    location.
                    """)
    @ApiResponse(responseCode = "200", description = "Bay updated successfully.")
    @ApiResponse(responseCode = "400", description = "locationId or bayId is not a UUID.")
    @ApiResponse(
            responseCode = "403",
            description = BAY_MANAGE_SCOPE_DENIED_DESCRIPTION,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Location or bay not found.")
    @ApiResponse(
            responseCode = "409",
            description = "Bay name already taken at this location, or a concurrent update won the version race.")
    @EmitEvent(id = "LOCATION_BAY_UPDATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + LocationPermissions.BAY_MANAGE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:bay:manage"})
    @PatchMapping("/{bayId}")
    public ResponseEntity<BayResponse> patchBay(
            @PathVariable String locationId,
            @PathVariable String bayId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Partial bay payload; only non-null fields are applied and all others"
                                    + " are left unchanged.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Take bay out of service",
                                                            value = "{\"status\":\"OUT_OF_SERVICE\"}")))
                    @RequestBody
                    BayPatchRequest patchRequest) {
        UUID location = requireInScope(locationId, LocationPermissions.BAY_MANAGE);
        return ResponseEntity.ok(bayService.patchBay(location, parseUuid(bayId), patchRequest));
    }

    @Operation(operationId = "deleteBay", summary = "Delete a Service Bay", description = """
                    Deletes a bay permanently by id and publishes a deletion fact so replica consumers drop \
                    the row from their dispatch and roster views.
                    Use this tool only when a bay was created in error; use patchBay with status \
                    OUT_OF_SERVICE instead to take a real bay out of service, which keeps it visible as \
                    inactive rather than removing it.
                    Preconditions: the location must exist and the bay must belong to it; there is no \
                    usage check, so callers must confirm the bay is not referenced by scheduled work first.
                    Required inputs: locationId and bayId (UUIDs) as path parameters; there is no request body.
                    Emits a LOCATION_BAY_DELETE event; the row is hard-deleted, not soft-deleted.
                    Returns 204 on success, 400 when either id does not parse as a UUID, 403 \
                    LOCATION_SCOPE_DENIED when a location-scoped location:bay:manage grant does not cover \
                    locationId (ADR-0061), and 404 when the location or bay does not exist.
                    """)
    @ApiResponse(responseCode = "204", description = "Bay deleted successfully.")
    @ApiResponse(responseCode = "400", description = "locationId or bayId is not a UUID.")
    @ApiResponse(
            responseCode = "403",
            description = BAY_MANAGE_SCOPE_DENIED_DESCRIPTION,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Location or bay not found.")
    @ApiResponse(responseCode = "409", description = "A concurrent update won the version race.")
    @EmitEvent(id = "LOCATION_BAY_DELETE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + LocationPermissions.BAY_MANAGE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:bay:manage"})
    @DeleteMapping("/{bayId}")
    public ResponseEntity<Void> deleteBay(
            @Parameter(description = "Location ID", example = "018e1c9f-6b5a-7890-abcd-1234567890ab") @PathVariable
                    String locationId,
            @Parameter(description = "ID of the bay to delete", example = "018e1c9f-6b5a-7890-abcd-1234567890cd")
                    @PathVariable
                    String bayId) {
        // Thrown rather than returned as a bare ResponseEntity.notFound(): every non-2xx response
        // must carry the ApiError envelope (docs/ERROR_ENVELOPE.md), and an empty body has no code,
        // message or correlationId. It also keeps one 404 contract for this operation -- a missing
        // *location* already surfaces through validateLocationExists as an enveloped 404, so
        // returning a bare body for a missing *bay* would give one endpoint two different 404s.
        UUID location = requireInScope(locationId, LocationPermissions.BAY_MANAGE);
        if (!bayService.deleteBay(location, parseUuid(bayId))) {
            throw new ResourceNotFoundException("Bay not found");
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Parses the path {@code locationId} and applies the caller's location scope to it
     * (ADR-0061 §3, #1872). Parsing comes first so a malformed id is a 400 for every caller,
     * scoped or not; the scope check comes before the service so a scoped caller is denied
     * on the location it named rather than on anything the service would go on to load.
     */
    private static UUID requireInScope(String locationId, String permission) {
        UUID location = parseUuid(locationId);
        SecurityContextHelper.locationScope().require(permission, location);
        return location;
    }

    /**
     * Strict parse: a value that is not a UUID is a 400, never a derived id. The former
     * {@code nameUUIDFromBytes} fallback mapped a typo onto a deterministic-but-nonexistent
     * location, which surfaced as a 404 for an unscoped caller and would surface as a 403 for a
     * scoped one — the scope check fails closed on an unknown id. One status for one fault.
     */
    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_UUID", exception);
        }
    }
}
