package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.CoverageRuleResponse;
import com.positivity.location.internal.dto.MobileUnitRequest;
import com.positivity.location.internal.dto.MobileUnitResponse;
import com.positivity.location.internal.exception.InvalidFieldException;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.MobileUnitService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
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
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Tag(name = "Mobile Unit API", description = "Operations for managing mobile units within locations")
@RestController
@RequestMapping("/v1/mobile-units")
@RequiredArgsConstructor
public class MobileUnitController {

    private static final String MOBILE_UNIT_EXAMPLE = """
            {"name":"Van 7",
             "baseLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
             "status":"ACTIVE",
             "travelBufferPolicyId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02",
             "notes":"Equipped with hydraulic lift",
             "serviceCapabilityCodes":["OIL-CHANGE-FULL-SYNTHETIC","BATTERY-REPLACEMENT"],
             "coverageRules":[{"serviceAreaId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03",
                               "ruleType":"SERVICE_AREA","priority":1,"validFrom":"2026-06-18"}]}
            """;

    private static final String COVERAGE_RULES_EXAMPLE = """
            {"rules":[{"serviceAreaId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03",
                       "ruleType":"SERVICE_AREA","priority":1,
                       "validFrom":"2026-06-18","validTo":"2026-12-31"}]}
            """;

    private static final String INCLUDE_COVERAGE_RULES = "coverageRules";

    private final MobileUnitService mobileUnitService;

    @Operation(operationId = "createMobileUnit", summary = "Create a New Mobile Service Unit", description = """
                    Creates a mobile service unit at a base location, with an optional travel buffer policy, \
                    capability list and initial coverage rules.
                    Use this tool when commissioning a van or truck that serves customers off-site; do not use \
                    patchMobileUnit, which updates an existing unit, and change coverage later with \
                    replaceCoverageRules.
                    Preconditions: a unit created with status ACTIVE must include travelBufferPolicyId, \
                    serviceCapabilityCodes and coverageRules; the base location, the travel buffer policy and \
                    every rule's service area must exist, every serviceCapabilityCode must be an active catalog \
                    operationCode known to the location service's catalog replica, each coverage rule's ruleType \
                    must be SERVICE_AREA or DISTANCE_TIER, DISTANCE_TIER coverage rules must be strictly \
                    ascending by maxDistance and end with one null catch-all tier, and the name must be unique \
                    (ignoring case) at the base location.
                    Required inputs: name and baseLocationId; status is ACTIVE or INACTIVE and defaults to \
                    INACTIVE when omitted, and travelBufferPolicyId, notes, serviceCapabilityCodes and \
                    coverageRules are optional for inactive units.
                    Emits a LOCATION_MOBILE_UNIT_CREATE event and persists any supplied coverage rules in the \
                    same transaction.
                    Returns 201 with the created unit; 400 VALIDATION_ERROR with fieldErrors for a blank name, a \
                    missing baseLocationId, an unknown status or a malformed coverage rule; 422 with fieldErrors \
                    (LOCATION_NOT_FOUND, TRAVEL_BUFFER_POLICY_NOT_FOUND, SERVICE_AREA_NOT_FOUND) when an id names \
                    nothing, and 422 for an incomplete ACTIVE unit or an unknown capability code; 409 \
                    MOBILE_UNIT_NAME_TAKEN when the name is already taken at the base location.
                    """)
    @ApiResponse(responseCode = "201", description = "Mobile unit created successfully.")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: blank name, missing baseLocationId, a status other than ACTIVE or"
                    + " INACTIVE, or a coverage rule with an unknown ruleType, no serviceAreaId, a negative"
                    + " priority or maxDistance, validTo before validFrom, or DISTANCE_TIER rules out of order."
                    + " fieldErrors names the field.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "MOBILE_UNIT_NAME_TAKEN: the name is already taken at the base location.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "LOCATION_NOT_FOUND, TRAVEL_BUFFER_POLICY_NOT_FOUND or SERVICE_AREA_NOT_FOUND (fieldErrors"
                    + " names the field) when an id names nothing; otherwise an ACTIVE unit without a travel buffer"
                    + " policy, capabilities and coverage rules, or a serviceCapabilityCode that is not an active"
                    + " catalog operation code.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "LOCATION_MOBILE_UNIT_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + LocationPermissions.MOBILE_UNIT_MANAGE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:mobile-unit:manage"})
    @PostMapping
    public ResponseEntity<MobileUnitResponse> createMobileUnit(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Mobile unit to create; an ACTIVE unit must arrive complete with its"
                                    + " travel buffer policy, capabilities and coverage rules.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Active mobile unit",
                                                            value = MOBILE_UNIT_EXAMPLE)))
                    @Valid
                    @RequestBody
                    MobileUnitRequest request) {
        log.info("Creating mobile unit with name(mask)={}", maskForLog(request != null ? request.getName() : null));
        return ResponseEntity.status(HttpStatus.CREATED).body(mobileUnitService.createMobileUnit(request));
    }

    @Operation(operationId = "listMobileUnits", summary = "List Mobile Units With Pagination", description = """
                    Lists mobile units as a page, ordered by name, with status, base location and travel buffer \
                    policy references; optionally narrowed to one base location and/or status, and optionally \
                    with each unit's coverage rules.
                    Use this tool to enumerate or browse units, or to read one shop's units (baseLocationId) and \
                    their coverage (include=coverageRules) in one request; use getMobileUnitById instead when \
                    the unit id is known, and findEligibleMobileUnits to match units to a service address.
                    Preconditions: none beyond the location:mobile-unit:read authority.
                    Required inputs: none; page defaults to 0 and size to 20. baseLocationId (UUID) keeps only \
                    units based there and is denied (403 LOCATION_SCOPE_DENIED) for a location-scoped caller \
                    outside their reach, status (ACTIVE or INACTIVE) keeps only units in that status, and \
                    include accepts coverageRules, which adds each unit's rules ordered by priority.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with a page of mobile units, empty when none match (including an unknown \
                    baseLocationId), and 400 VALIDATION_ERROR for an unknown status or include value.
                    """)
    @ApiResponse(responseCode = "200", description = "Mobile units retrieved successfully.")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: baseLocationId is not a UUID, status is not ACTIVE or INACTIVE, or"
                    + " include names something other than coverageRules.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks location:mobile-unit:read, or (LOCATION_SCOPE_DENIED) the named"
                    + " baseLocationId is outside a location-scoped caller's reach.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + LocationPermissions.MOBILE_UNIT_READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:mobile-unit:read"})
    @GetMapping
    public ResponseEntity<Page<MobileUnitResponse>> listMobileUnits(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(
                            description = "Only units based at this location",
                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01")
                    @RequestParam(required = false)
                    UUID baseLocationId,
            @Parameter(description = "Only units in this status (ACTIVE or INACTIVE)", example = "ACTIVE")
                    @RequestParam(required = false)
                    String status,
            @Parameter(
                            description = "Related data to embed; coverageRules adds each unit's coverage rules",
                            example = INCLUDE_COVERAGE_RULES)
                    @RequestParam(required = false)
                    List<String> include) {
        boolean includeCoverageRules = parseInclude(include);
        // A named base location is gated like BayController.listBays: a scoped caller asking for
        // one shop's units is denied outside their reach (ADR-0061, location-scope.yaml). The
        // unfiltered list keeps the tenant-wide view getMobileUnitById also gives.
        if (baseLocationId != null) {
            SecurityContextHelper.locationScope().require(LocationPermissions.MOBILE_UNIT_READ, baseLocationId);
        }
        return ResponseEntity.ok(mobileUnitService.list(page, size, baseLocationId, status, includeCoverageRules));
    }

    /** {@code include} is a comma-separated or repeated list; coverageRules is the only value today. */
    private static boolean parseInclude(List<String> include) {
        if (include == null) {
            return false;
        }
        List<String> values = new ArrayList<>();
        for (String entry : include) {
            for (String value : entry.split(",")) {
                if (!value.isBlank()) {
                    values.add(value.trim());
                }
            }
        }
        for (String value : values) {
            if (!INCLUDE_COVERAGE_RULES.equals(value)) {
                throw InvalidFieldException.invalid("include", "include accepts only coverageRules");
            }
        }
        return !values.isEmpty();
    }

    @Operation(operationId = "getMobileUnitById", summary = "Get a Mobile Unit by Identifier", description = """
                    Returns a single mobile unit with its status, base location, capability ids and travel buffer \
                    policy reference.
                    Use this tool when the unit id is already known; use listMobileUnits instead to enumerate, \
                    and listCoverageRules to read the unit's coverage separately.
                    Preconditions: the mobile unit must exist.
                    Required inputs: id (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no mobile unit exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Mobile unit returned.")
    @ApiResponse(
            responseCode = "404",
            description = "Mobile unit not found.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + LocationPermissions.MOBILE_UNIT_READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:mobile-unit:read"})
    @GetMapping("/{id}")
    public ResponseEntity<MobileUnitResponse> getMobileUnitById(
            @Parameter(description = "Mobile unit ID") @PathVariable UUID id) {
        return mobileUnitService
                .getById(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mobile unit not found"));
    }

    @Operation(operationId = "patchMobileUnit", summary = "Patch Fields of a Mobile Unit", description = """
                    Applies a partial update to a mobile unit, accepting the keys name, status, notes, \
                    travelBufferPolicyId and serviceCapabilityCodes.
                    Use this tool for status transitions, travel-buffer-policy reassignment and replacing the \
                    unit's capability claim; use replaceCoverageRules instead to change where the unit operates.
                    Preconditions: the unit must exist. The unit as it stands after the patch must satisfy what \
                    create demands of an ACTIVE unit, so an ACTIVE result needs a travelBufferPolicyId, at least \
                    one serviceCapabilityCode and at least one coverage rule already on the unit; activating an \
                    incomplete unit means calling replaceCoverageRules first and then sending the status with the \
                    policy and capabilities. serviceCapabilityCodes replaces the whole claim and every code must \
                    be an active catalog operationCode known to the location service's catalog replica.
                    Required inputs: id (UUID) as a path parameter and a JSON object of the fields to change. \
                    name must be non-blank text, unique (ignoring case) at the unit's base location; status must \
                    be ACTIVE or INACTIVE (any case; null is refused); notes is text or null; \
                    travelBufferPolicyId is null to clear it or the id of an existing policy; \
                    serviceCapabilityCodes is an array; other keys are ignored.
                    Emits a LOCATION_MOBILE_UNIT_UPDATE event.
                    Returns 200 with the updated unit; 404 NOT_FOUND when the unit does not exist; 400 \
                    VALIDATION_ERROR with fieldErrors for a value of the wrong shape; 409 MOBILE_UNIT_NAME_TAKEN \
                    when the new name is taken at the base location, or 409 when a concurrent update won the \
                    version race; 422 TRAVEL_BUFFER_POLICY_NOT_FOUND with fieldErrors for an unknown policy, and \
                    422 when the result would be an incomplete ACTIVE unit or a capability code is unknown; \
                    nothing is saved on any refusal.
                    """)
    @ApiResponse(responseCode = "200", description = "Mobile unit updated.")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: blank or non-text name, a status other than ACTIVE or INACTIVE (null"
                    + " included), non-text notes, a travelBufferPolicyId that is not a UUID, or"
                    + " serviceCapabilityCodes that is not an array. fieldErrors names the field.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks location:mobile-unit:manage.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Mobile unit not found.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "MOBILE_UNIT_NAME_TAKEN when the new name is taken at the base location, or"
                    + " OPTIMISTIC_LOCK_FAILED when a concurrent update won the version race.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "TRAVEL_BUFFER_POLICY_NOT_FOUND (fieldErrors names travelBufferPolicyId) for an unknown"
                    + " policy; otherwise the unit would be ACTIVE after the patch without a travel buffer policy,"
                    + " capabilities and coverage rules, or a serviceCapabilityCode is not an active catalog"
                    + " operation code.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "LOCATION_MOBILE_UNIT_UPDATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + LocationPermissions.MOBILE_UNIT_MANAGE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:mobile-unit:manage"})
    @PatchMapping("/{id}")
    public ResponseEntity<MobileUnitResponse> patchMobileUnit(
            @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Free-form patch object; only the keys name, status, notes,"
                                    + " travelBufferPolicyId and serviceCapabilityCodes are recognized.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Deactivate unit",
                                                            value =
                                                                    "{\"status\":\"INACTIVE\",\"notes\":\"Winter maintenance\"}")))
                    @RequestBody
                    Map<String, Object> patch) {
        return ResponseEntity.ok(mobileUnitService.patch(id, patch));
    }

    @Operation(operationId = "deleteMobileUnit", summary = "Delete a Mobile Unit", description = """
                    Deletes a mobile unit permanently by id, removing its coverage rules and capability \
                    assignments, and publishes a deletion fact so replica consumers drop the row from their \
                    dispatch and roster views.
                    Use this tool only when a unit was created in error; use patchMobileUnit with status \
                    INACTIVE instead to stand down a real unit, which keeps it visible as inactive rather \
                    than removing it.
                    Preconditions: the unit must exist; there is no usage check, so callers must confirm the \
                    unit is not referenced by scheduled work first.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    Emits a LOCATION_MOBILE_UNIT_DELETE event; the row is hard-deleted, not soft-deleted, and \
                    its coverage rules are deleted with it.
                    Returns 204 on success and 404 when the mobile unit does not exist.
                    """)
    @ApiResponse(responseCode = "204", description = "Mobile unit deleted successfully.")
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks location:mobile-unit:manage.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Mobile unit not found.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "A concurrent update won the version race.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "LOCATION_MOBILE_UNIT_DELETE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + LocationPermissions.MOBILE_UNIT_MANAGE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:mobile-unit:manage"})
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMobileUnit(
            @Parameter(
                            description = "ID of the mobile unit to delete",
                            example = "018e1c9f-6b5a-7890-abcd-1234567890ab")
                    @PathVariable
                    UUID id) {
        // Thrown rather than returned as a bare ResponseEntity.notFound(): every non-2xx response
        // must carry the ApiError envelope (../durion/docs/architecture/api/ERROR_ENVELOPE.md), and an empty body has
        // no code,
        // message or correlationId for the caller or the logs to key on.
        if (!mobileUnitService.deleteMobileUnit(id)) {
            throw new ResourceNotFoundException("Mobile unit not found");
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(
            operationId = "replaceCoverageRules",
            summary = "Replace Coverage Rules for Mobile Unit",
            description = """
                    Atomically replaces the full set of coverage rules for a mobile unit, deleting the existing \
                    rules and inserting the supplied ones in one transaction.
                    Use this tool whenever coverage changes, sending the complete desired rule set; do not use \
                    patchMobileUnit, which cannot modify coverage.
                    Preconditions: the mobile unit must exist; every rule's serviceAreaId must name an existing \
                    service area; DISTANCE_TIER rules must be strictly ascending by maxDistance and end with one \
                    null catch-all tier; an ACTIVE unit must keep at least one rule. The replacement set is \
                    checked in full before the existing rules are touched, so a refusal changes nothing.
                    Required inputs: id (UUID) as a path parameter and a body of the form {"rules": [...]}, each \
                    rule carrying ruleType (SERVICE_AREA or DISTANCE_TIER, any case) and serviceAreaId, and \
                    optionally priority (non-negative, defaults to 0), validFrom, validTo (not before validFrom) \
                    and maxDistance (non-negative).
                    Emits a LOCATION_COVERAGE_RULES_REPLACE event.
                    Returns 200 with the saved rules ordered by priority; 400 VALIDATION_ERROR with fieldErrors \
                    (rules[i].field) for a malformed rule or tiers out of order; 404 when the mobile unit does not \
                    exist; 422 SERVICE_AREA_NOT_FOUND with fieldErrors for an unknown service area, and 422 when \
                    the set would leave an ACTIVE unit with no rules. An omitted or empty rules array clears all \
                    coverage of an INACTIVE unit.
                    """)
    @ApiResponse(responseCode = "200", description = "Coverage rules replaced successfully.")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: rules is not an array, or a rule has an unknown ruleType, no"
                    + " serviceAreaId, a value of the wrong type, a negative priority or maxDistance, validTo before"
                    + " validFrom, or the DISTANCE_TIER rules are out of order. fieldErrors names the field.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Mobile unit not found.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "SERVICE_AREA_NOT_FOUND (fieldErrors names rules[i].serviceAreaId) for an unknown service"
                    + " area, or the replacement would leave an ACTIVE unit with no coverage rules.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "LOCATION_COVERAGE_RULES_REPLACE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + LocationPermissions.MOBILE_UNIT_MANAGE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:mobile-unit:manage"})
    @PutMapping("/{id}/coverage-rules")
    public ResponseEntity<List<CoverageRuleResponse>> replaceCoverageRules(
            @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Envelope holding the complete replacement rule set under the"
                                    + " \"rules\" key; existing rules not present here are deleted.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Single service-area rule",
                                                            value = COVERAGE_RULES_EXAMPLE)))
                    @RequestBody
                    Map<String, Object> payload) {
        return ResponseEntity.ok(mobileUnitService.replaceCoverageRules(id.toString(), rulesOf(payload)));
    }

    /** The {@code rules} array of the PUT body, each element an object; 400 naming the field otherwise. */
    private static List<Map<String, Object>> rulesOf(Map<String, Object> payload) {
        Object raw = payload == null ? null : payload.get("rules");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw InvalidFieldException.invalid("rules", "rules must be an array");
        }
        List<Map<String, Object>> rules = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> rule)) {
                throw InvalidFieldException.invalid("rules[" + i + "]", "coverage rule must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) rule;
            rules.add(typed);
        }
        return rules;
    }

    @Operation(operationId = "listCoverageRules", summary = "Get Coverage Rules of Mobile Unit", description = """
                    Returns the coverage rules of a mobile unit ordered by ascending priority.
                    Use this tool to inspect where a unit currently operates; use findEligibleMobileUnits instead \
                    to answer which units cover a given postal code.
                    Preconditions: none; an unknown unit id yields an empty list rather than an error.
                    Required inputs: id (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the ordered rule list, empty when the unit has no rules or does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Coverage rules returned.")
    @PreAuthorize("hasAuthority('" + LocationPermissions.MOBILE_UNIT_READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:mobile-unit:read"})
    @GetMapping("/{id}/coverage-rules")
    public ResponseEntity<List<CoverageRuleResponse>> getCoverageRules(@PathVariable UUID id) {
        return ResponseEntity.ok(mobileUnitService.getCoverageRules(id));
    }

    private String maskForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String sanitized =
                value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }
}
