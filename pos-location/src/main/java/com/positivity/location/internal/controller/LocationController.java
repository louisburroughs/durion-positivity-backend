package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.LocationDescendantResponseDTO;
import com.positivity.location.internal.dto.LocationParentResponseDTO;
import com.positivity.location.internal.dto.LocationPatchRequest;
import com.positivity.location.internal.dto.LocationRef;
import com.positivity.location.internal.dto.LocationRequestDTO;
import com.positivity.location.internal.dto.LocationResponseDTO;
import com.positivity.location.internal.dto.LocationValidationResponseDTO;
import com.positivity.location.internal.dto.PersonDTO;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.LocationRosterService;
import com.positivity.location.internal.service.LocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "Location API", description = "Operations related to locations and their relationships")
@RestController
@RequestMapping("/v1/locations")
@RequiredArgsConstructor
public class LocationController {

    private static final String LOCATION_EXAMPLE = """
            {"name":"Downtown Service Center",
             "code":"LOC-001",
             "type":{"name":"STORE"},
             "timezone":"America/Chicago",
             "addressLine1":"123 Main St",
             "city":"Springfield",
             "state":"IL",
             "postalCode":"62704",
             "country":"US",
             "active":true,
             "responsiblePersonId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
             "operatingHours":[{"dayOfWeek":"MONDAY","openTime":"08:00","closeTime":"17:00"}],
             "checkInBufferMinutes":15,
             "cleanupBufferMinutes":10}
            """;

    private final LocationService locationService;
    private final LocationRosterService locationRosterService;

    @Operation(operationId = "listLocations", summary = "List All Locations Without Pagination", description = """
                    Lists every location in the system as a flat, unpaginated collection with address, status and \
                    type details.
                    Use this tool when a complete location inventory is needed at once; use getLocationRoster \
                    instead for a paginated sync feed with status and updated-since filtering, and do not use it \
                    to fetch a single known id, which is getLocationById.
                    Preconditions: none beyond the location:read authority; an empty system returns an empty list.
                    Required inputs: none; there are no parameters and no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the full list; there are no business error conditions for this operation.
                    """)
    @ApiResponse(responseCode = "200", description = "List of locations returned successfully.")
    @PreAuthorize("hasAuthority('" + LocationPermissions.READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:read"})
    @GetMapping
    public List<LocationResponseDTO> getAllLocations() {
        return locationService.getAllLocationsDto();
    }

    /**
     * Alias controller for location roster endpoint used by contract behavior
     * tests.
     *
     * Issue: CAP-214 #40
     */
    @Operation(operationId = "getLocationRoster", summary = "Get Paginated Location Roster for Sync", description = """
                    Returns a paginated roster of lightweight location references (id, name, code, status, \
                    hrLocationId, timezone, updatedAt) for sync consumers.
                    Use this tool when incrementally synchronising locations into another system; do not use \
                    listLocations, which returns full unpaginated location payloads.
                    Preconditions: none beyond the location:read authority.
                    Required inputs: none are mandatory; status filters exactly on the stored status value such \
                    as ACTIVE, sinceUpdatedAt is an ISO-8601 instant returning only rows updated after it, and \
                    standard page, size and sort parameters control paging.
                    Emits a LOCATION_ROSTER_GET event; no location state changes.
                    Returns 200 with a page of location refs; an unknown status value yields an empty page rather \
                    than an error.
                    """)
    @ApiResponse(responseCode = "200", description = "Location roster returned successfully.")
    @PreAuthorize("hasAuthority('" + LocationPermissions.READ + "')")
    @EmitEvent(id = "LOCATION_ROSTER_GET", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:read"})
    @GetMapping("/roster")
    public Page<LocationRef> getRoster(
            @Parameter(description = "Optional status filter", example = "ACTIVE") @RequestParam(required = false)
                    String status,
            @Parameter(description = "Optional since-updated-at filter", example = "2026-01-01T00:00:00Z")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant sinceUpdatedAt,
            @ParameterObject Pageable pageable) {
        return locationRosterService.getRoster(status, sinceUpdatedAt, pageable);
    }

    @Operation(operationId = "getLocationById", summary = "Get Location by Unique Identifier", description = """
                    Returns the full location record, including address fields, active flag, responsible person \
                    id and type classification, for a known id.
                    Use this tool when the location id is already known; use listLocations instead to enumerate, \
                    and use validateLocation when only existence and active state are needed.
                    Preconditions: the location must exist.
                    Required inputs: locationId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no location exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Location found and returned.")
    @ApiResponse(responseCode = "404", description = "Location not found.")
    @PreAuthorize("hasAuthority('" + LocationPermissions.READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:read"})
    @GetMapping("/{locationId}")
    public ResponseEntity<LocationResponseDTO> getLocationById(
            @Parameter(description = "ID of the location to retrieve", example = "018e1c9f-6b5a-7890-abcd-1234567890ab")
                    @PathVariable
                    UUID locationId) {
        return locationService
                .getLocationByIdDto(locationId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(operationId = "getTopLevelLocation", summary = "Get Top-Level Default Location", description = """
                    Returns the platform's top-level location: the active root of the parent-child hierarchy \
                    (a location that is a parent of others but a child of none), falling back to the oldest \
                    active location when no hierarchy edges exist. The pick is deterministic (ties break on id).
                    Use this tool when a caller needs a default location, e.g. resolving a fallback for users \
                    with no primary staffing assignment; do not use it when the id is already known — call \
                    getLocationById instead.
                    Preconditions: at least one active location must exist.
                    Required inputs: none; there are no parameters and no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no active location exists.
                    """)
    @ApiResponse(responseCode = "200", description = "Top-level location resolved and returned.")
    @ApiResponse(responseCode = "404", description = "No active location exists.")
    @PreAuthorize("hasAuthority('" + LocationPermissions.READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:read"})
    @GetMapping("/top-level")
    public ResponseEntity<LocationResponseDTO> getTopLevelLocation() {
        return locationService
                .getTopLevelLocationDto()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            operationId = "validateLocation",
            summary = "Validate Location Existence and Active State",
            description = """
                    Returns an existence-and-active-state check for a location id without transferring the full \
                    record.
                    Use this tool for inter-service reference validation before storing a locationId; do not use \
                    getLocationById, which returns the full payload and answers 404 for unknown ids.
                    Preconditions: none; unknown ids are a valid input.
                    Required inputs: locationId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 always, with exists=false and active=false when the id is unknown, so callers \
                    must inspect the flags rather than the status code.
                    """)
    @ApiResponse(responseCode = "200", description = "Validation result returned.")
    @PreAuthorize("hasAuthority('" + LocationPermissions.READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:read"})
    @GetMapping("/{locationId}/validation")
    public ResponseEntity<LocationValidationResponseDTO> validateLocation(
            @Parameter(description = "ID of the location to validate", example = "018e1c9f-6b5a-7890-abcd-1234567890ab")
                    @PathVariable
                    UUID locationId) {
        return ResponseEntity.ok(locationService.getLocationValidation(locationId));
    }

    @Operation(operationId = "createLocation", summary = "Create a New Location Record", description = """
                    Creates a location with address, timezone, operating hours, scheduling buffers and type \
                    classification, defaulting status to ACTIVE.
                    Use this tool when registering a new physical or logical site; do not use updateLocation, \
                    which replaces an existing location, and use bulkIngestLocations for batch imports.
                    Preconditions: no existing location may share the same name (case-insensitive) or code, and a \
                    type referenced by id must already exist; a type given only by name is created on the fly.
                    Required inputs: name, code and type (id or name); timezone must be a valid IANA zone id, \
                    operatingHours entries must have unique dayOfWeek values with openTime before closeTime, and \
                    active defaults to true.
                    Emits a LOCATION_LOCATION_CREATE event and publishes a location fact for replica consumers.
                    Returns 409 when the name or code is already taken, 422 when the timezone or operating hours \
                    are invalid, and 400 when a referenced location type id is unknown.
                    """)
    @ApiResponse(responseCode = "201", description = "Location created successfully.")
    @ApiResponse(responseCode = "409", description = "Location name or code already taken.")
    @ApiResponse(responseCode = "422", description = "Invalid timezone or operating hours.")
    @EmitEvent(id = "LOCATION_LOCATION_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + LocationPermissions.WRITE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:write"})
    @PostMapping
    public ResponseEntity<LocationResponseDTO> createLocation(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Location to create, including its type and optional address,"
                                    + " timezone and operating-hours configuration.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Store location", value = LOCATION_EXAMPLE)))
                    @Valid
                    @RequestBody
                    LocationRequestDTO location) {
        LocationResponseDTO saved = locationService.createLocation(location);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @Operation(operationId = "updateLocation", summary = "Update an Existing Location Fully", description = """
                    Replaces the mutable fields of an existing location with the supplied full payload, including \
                    address, timezone, operating hours and type.
                    Use this tool when the complete corrected state of a location is known; use patchLocation \
                    instead to change selected fields and leave the rest untouched.
                    Preconditions: the location must exist, and no other location may already use the new name.
                    Required inputs: locationId (UUID) as a path parameter plus a full body with name, code and \
                    type; omitted optional fields are overwritten with the request values, not preserved.
                    Emits a LOCATION_LOCATION_UPDATE event and publishes a location fact for replica consumers.
                    Returns 404 when the location does not exist, 409 when the name or code collides with another \
                    location, and 422 when the timezone or operating hours are invalid.
                    """)
    @ApiResponse(responseCode = "200", description = "Location updated successfully.")
    @ApiResponse(responseCode = "404", description = "Location not found.")
    @ApiResponse(responseCode = "409", description = "Location name or code already taken.")
    @ApiResponse(responseCode = "422", description = "Invalid timezone or operating hours.")
    @EmitEvent(id = "LOCATION_LOCATION_UPDATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + LocationPermissions.WRITE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:write"})
    @PutMapping("/{locationId}")
    public ResponseEntity<LocationResponseDTO> updateLocation(
            @Parameter(description = "ID of the location to update", example = "018e1c9f-6b5a-7890-abcd-1234567890ab")
                    @PathVariable
                    UUID locationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Full replacement state for the location; every mutable field is"
                                    + " overwritten with the values supplied here.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Full replacement",
                                                            value = LOCATION_EXAMPLE)))
                    @Valid
                    @RequestBody
                    LocationRequestDTO location) {
        return locationService
                .updateLocation(locationId, location)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(operationId = "patchLocation", summary = "Patch Selected Fields of a Location", description = """
                    Applies a partial update to a location, changing only the supplied fields: name, status, \
                    timezone, operatingHours, holidayClosures, checkInBufferMinutes and cleanupBufferMinutes.
                    Use this tool for targeted edits such as deactivation or hours changes; do not use \
                    updateLocation, which overwrites every mutable field including address and type.
                    Preconditions: the location must exist, and a new name must not be used by another location.
                    Required inputs: locationId (UUID) as a path parameter and a body with at least one field; \
                    status only accepts the value INACTIVE to deactivate, and reactivation is not supported \
                    through this operation.
                    Emits a LOCATION_PATCH event and publishes a location fact for replica consumers.
                    Returns 404 when the location does not exist, 409 when the new name is taken, and 422 when a \
                    supplied timezone or operating-hours entry is invalid.
                    """)
    @ApiResponse(responseCode = "200", description = "Location patched successfully.")
    @ApiResponse(responseCode = "404", description = "Location not found.")
    @ApiResponse(responseCode = "409", description = "Location name already taken.")
    @ApiResponse(responseCode = "422", description = "Invalid timezone or operating hours.")
    @PatchMapping("/{locationId}")
    @PreAuthorize("hasAuthority('" + LocationPermissions.WRITE + "')")
    @EmitEvent(id = "LOCATION_PATCH", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:write"})
    public ResponseEntity<LocationResponseDTO> patchLocation(
            @Parameter(description = "ID of the location to patch", example = "018e1c9f-6b5a-7890-abcd-1234567890ab")
                    @PathVariable
                    UUID locationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Partial location payload; only non-null fields are applied and all"
                                    + " others are left unchanged.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Deactivate location",
                                                            value = "{\"status\":\"INACTIVE\"}")))
                    @RequestBody
                    LocationPatchRequest patch) {
        return ResponseEntity.ok(locationService.patchLocation(locationId, patch));
    }

    @Operation(operationId = "deleteLocation", summary = "Delete a Location by Identifier", description = """
                    Deletes a location permanently by id and publishes a deletion fact so replica consumers drop \
                    the row.
                    Use this tool only when a location was created in error; use patchLocation with status \
                    INACTIVE instead to retire a real site while preserving history.
                    Preconditions: the location must exist; there is no child-relationship or usage check, so \
                    callers must confirm the location is unreferenced first.
                    Required inputs: locationId (UUID) as a path parameter; there is no request body.
                    Emits a LOCATION_LOCATION_DELETE event; the row is hard-deleted, not soft-deleted.
                    Returns 204 on success and 404 when the location does not exist.
                    """)
    @ApiResponse(responseCode = "204", description = "Location deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Location not found.")
    @PreAuthorize("hasAuthority('" + LocationPermissions.WRITE + "')")
    @DeleteMapping("/{locationId}")
    @EmitEvent(id = "LOCATION_LOCATION_DELETE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:write"})
    public ResponseEntity<Void> deleteLocation(
            @Parameter(description = "ID of the location to delete", example = "018e1c9f-6b5a-7890-abcd-1234567890ab")
                    @PathVariable
                    UUID locationId) {
        if (locationService.getLocationByIdDto(locationId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        locationService.deleteLocation(locationId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            operationId = "addLocationParent",
            summary = "Add Typed Parent Relationship to Location",
            description = """
                    Creates a typed parent-child edge between two existing locations, giving the child at most \
                    one parent per relationship type.
                    Use this tool when building the location hierarchy; do not use listLocationChildren or \
                    listLocationDescendants, which only read the hierarchy.
                    Preconditions: both locations must exist, the child must not already have a parent of that \
                    type, the pair must not already be linked in either direction, and the parent must not be a \
                    descendant of the child because cycles are forbidden by ADR-0016.
                    Required inputs: childId and parentId (UUIDs) as path parameters and a parentType query \
                    parameter, one of HOME_OFFICE, HEADQUARTERS, REGION, DISTRICT, PHYSICAL, ORGANIZATIONAL, \
                    FINANCIAL or SHIPPING.
                    Emits a LOCATION_PARENT_ADD event and republishes the child's location fact, which carries \
                    the new edge to replica consumers.
                    Returns 400 when parentType is not a recognized value; self-parenting, duplicate, inverse or \
                    circular relationships are rejected before the edge is written.
                    """)
    @ApiResponse(responseCode = "200", description = "Parent relationship added successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid parentType value.")
    @EmitEvent(id = "LOCATION_PARENT_ADD", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + LocationPermissions.WRITE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:write"})
    @PostMapping("/{childId}/parents/{parentId}")
    public ResponseEntity<LocationParentResponseDTO> addParent(
            @Parameter(description = "ID of the child location", example = "018e1c9f-6b5a-7890-abcd-1234567890ab")
                    @PathVariable
                    UUID childId,
            @Parameter(description = "ID of the parent location", example = "018e1c9f-0000-7890-abcd-1234567890ab")
                    @PathVariable
                    UUID parentId,
            @Parameter(description = "Type of the parent relationship") @RequestParam String parentType) {
        LocationParentResponseDTO parent = locationService.addParent(childId, parentId, parentType);
        return ResponseEntity.ok(parent);
    }

    @Operation(
            operationId = "listLocationParents",
            summary = "List All Location Parent Relationships",
            description = """
                    Lists every parent-child relationship edge across all locations, with parentId, childId and \
                    parentType.
                    Use this tool when a full hierarchy dump is needed; use listLocationChildren instead to read \
                    the children of one location, or listLocationDescendants for a deep traversal.
                    Preconditions: none beyond the location:read authority.
                    Required inputs: none; there are no parameters and no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the full unpaginated edge list.
                    """)
    @ApiResponse(responseCode = "200", description = "List of location parents returned successfully.")
    @PreAuthorize("hasAuthority('" + LocationPermissions.READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:read"})
    @GetMapping("/parents")
    public List<LocationParentResponseDTO> getAllParents() {
        return locationService.getAllParentsDto();
    }

    @Operation(operationId = "listLocationChildren", summary = "List Direct Children of a Location", description = """
                    Returns the direct child locations of a parent location, optionally restricted to one \
                    relationship type.
                    Use this tool for one level of hierarchy; use listLocationDescendants instead when the whole \
                    subtree with depth information is needed.
                    Preconditions: none; an unknown or childless parent id yields an empty list rather than an \
                    error.
                    Required inputs: locationId (UUID) as a path parameter; parentType is optional and, when \
                    omitted, edges of every relationship type are returned.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 400 when parentType is supplied but is not a recognized ParentType value.
                    """)
    @ApiResponse(responseCode = "200", description = "List of child locations returned successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid parentType value.")
    @PreAuthorize("hasAuthority('" + LocationPermissions.READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:read"})
    @GetMapping("/{locationId}/children")
    public List<LocationResponseDTO> getAllChildren(
            @Parameter(description = "ID of the parent location", example = "018e1c9f-6b5a-7890-abcd-1234567890ab")
                    @PathVariable
                    UUID locationId,
            @Parameter(description = "Optional parent relationship type filter") @RequestParam(required = false)
                    String parentType) {
        return locationService.getAllChildrenDto(locationId, parentType);
    }

    @Operation(
            operationId = "getLocationResponsiblePerson",
            summary = "Get Responsible Person for a Location",
            description = """
                    Returns the person responsible for a location, resolved from the people-contact replica with \
                    name, email and phone details.
                    Use this tool to find who owns a site operationally; do not use getLocationById, which \
                    returns only the responsiblePersonId without contact details.
                    Preconditions: the location must exist and have a responsiblePersonId assigned.
                    Required inputs: locationId (UUID) as a path parameter.
                    Emits a LOCATION_RESPONSIBLE_PERSON_GET event; no state changes.
                    Returns 404 when the location does not exist or no responsible person is assigned; when the \
                    replica row has not yet arrived, a 200 with only the person id is returned and names follow \
                    with the feed.
                    """)
    @ApiResponse(responseCode = "200", description = "Responsible person found and returned.")
    @ApiResponse(responseCode = "404", description = "Responsible person not found.")
    @EmitEvent(id = "LOCATION_RESPONSIBLE_PERSON_GET", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + LocationPermissions.READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:read"})
    @GetMapping("/{locationId}/responsible-person")
    public ResponseEntity<PersonDTO> getResponsiblePerson(
            @Parameter(description = "ID of the location", example = "018e1c9f-6b5a-7890-abcd-1234567890ab")
                    @PathVariable
                    UUID locationId) {
        PersonDTO person = locationService.getResponsiblePerson(locationId);
        if (person == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(person);
    }

    /**
     * Descendants traversal endpoint for topology consumers.
     *
     * Issue: CAP-214 #655
     */
    @Operation(
            operationId = "listLocationDescendants",
            summary = "List All Descendants of a Location",
            description = """
                    Walks typed parent-child edges downward from a location and returns every descendant as a \
                    flat list with its immediate parent id and depth, where depth 1 is a direct child.
                    Use this tool when a whole subtree is needed in one call; use listLocationChildren instead \
                    for only the direct children of one location.
                    Preconditions: the root location must exist; traversal is cycle-safe and silently truncates \
                    at depth 20.
                    Required inputs: locationId (UUID) as a path parameter; parentType defaults to PHYSICAL when \
                    omitted.
                    Emits a LOCATION_DESCENDANTS_GET event; no state changes.
                    Returns 404 when the root location does not exist and 400 when parentType is not a recognized \
                    ParentType value.
                    """)
    @ApiResponse(responseCode = "200", description = "List of descendant locations returned successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid parentType value.")
    @ApiResponse(responseCode = "404", description = "Location not found.")
    @PreAuthorize("hasAuthority('" + LocationPermissions.READ + "')")
    @EmitEvent(id = "LOCATION_DESCENDANTS_GET", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:read"})
    @GetMapping("/{locationId}/descendants")
    public List<LocationDescendantResponseDTO> getDescendants(
            @Parameter(description = "ID of the root location", example = "018e1c9f-6b5a-7890-abcd-1234567890ab")
                    @PathVariable
                    UUID locationId,
            @Parameter(description = "Parent relationship type to traverse (defaults to PHYSICAL)")
                    @RequestParam(required = false, defaultValue = "PHYSICAL")
                    String parentType) {
        return locationService.getDescendantsDto(locationId, parentType);
    }
}
