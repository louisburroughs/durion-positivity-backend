package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.CoverageRuleResponse;
import com.positivity.location.internal.dto.MobileUnitRequest;
import com.positivity.location.internal.dto.MobileUnitResponse;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.MobileUnitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
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
             "capabilityIds":["018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10"],
             "coverageRules":[{"serviceAreaId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03",
                               "ruleType":"SERVICE_AREA","priority":1,"validFrom":"2026-06-18"}]}
            """;

    private static final String COVERAGE_RULES_EXAMPLE = """
            {"rules":[{"serviceAreaId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03",
                       "ruleType":"SERVICE_AREA","priority":1,
                       "validFrom":"2026-06-18","validTo":"2026-12-31"}]}
            """;

    private final MobileUnitService mobileUnitService;

    @Operation(operationId = "createMobileUnit", summary = "Create a New Mobile Service Unit", description = """
                    Creates a mobile service unit with an optional base location, travel buffer policy, \
                    capability list and initial coverage rules.
                    Use this tool when commissioning a van or truck that serves customers off-site; do not use \
                    patchMobileUnit, which updates an existing unit, and change coverage later with \
                    replaceCoverageRules.
                    Preconditions: a unit created with status ACTIVE must include travelBufferPolicyId, \
                    capabilityIds and coverageRules; the travel buffer policy must exist, capability ids or codes \
                    must resolve to registered capabilities, DISTANCE_TIER coverage rules must be strictly \
                    ascending by maxDistance and end with a null catch-all tier, and the name must be unique at \
                    the base location.
                    Required inputs: name; status defaults to INACTIVE when omitted, and baseLocationId, \
                    travelBufferPolicyId, notes, capabilityIds and coverageRules are optional for inactive units.
                    Emits a LOCATION_MOBILE_UNIT_CREATE event and persists any supplied coverage rules in the \
                    same transaction.
                    Returns 201 with the created unit and 409 when the name is already taken at the base \
                    location.
                    """)
    @ApiResponse(responseCode = "201", description = "Mobile unit created successfully.")
    @ApiResponse(responseCode = "409", description = "Mobile unit name already taken at the base location.")
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
                    @RequestBody
                    MobileUnitRequest request) {
        log.info("Creating mobile unit with name(mask)={}", maskForLog(request != null ? request.getName() : null));
        return ResponseEntity.status(HttpStatus.CREATED).body(mobileUnitService.createMobileUnit(request));
    }

    @Operation(operationId = "listMobileUnits", summary = "List Mobile Units With Pagination", description = """
                    Lists all mobile units as a page with status, base location and travel buffer policy \
                    references.
                    Use this tool to enumerate or browse units; use getMobileUnitById instead when the unit id is \
                    known, and findEligibleMobileUnits to match units to a service address.
                    Preconditions: none beyond the location:mobile-unit:read authority.
                    Required inputs: none; page defaults to 0 and size to 20.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with a page of mobile units, empty when none exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Mobile units retrieved successfully.")
    @PreAuthorize("hasAuthority('" + LocationPermissions.MOBILE_UNIT_READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:mobile-unit:read"})
    @GetMapping
    public ResponseEntity<Page<MobileUnitResponse>> listMobileUnits(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(mobileUnitService.list(page, size));
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
    @ApiResponse(responseCode = "404", description = "Mobile unit not found.")
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
                    Applies a partial update to a mobile unit, accepting the keys name, status, notes and \
                    travelBufferPolicyId.
                    Use this tool for status transitions and travel-buffer-policy reassignment; use \
                    replaceCoverageRules instead to change where the unit operates.
                    Preconditions: none are enforced; when the unit id does not exist nothing is persisted and a \
                    synthesized response built from the patch is echoed back, so callers must verify existence \
                    first with getMobileUnitById.
                    Required inputs: id (UUID) as a path parameter and a JSON object of the fields to change; \
                    status values are upper-cased and a blank status normalizes to INACTIVE.
                    Emits a LOCATION_MOBILE_UNIT_UPDATE event.
                    Returns 200 even for unknown ids (with the unpersisted echo) and 409 when a name change \
                    collides with another unit at the same base location.
                    """)
    @ApiResponse(responseCode = "200", description = "Mobile units managed successfully.")
    @ApiResponse(responseCode = "409", description = "Mobile unit name already taken at the base location.")
    @EmitEvent(id = "LOCATION_MOBILE_UNIT_UPDATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + LocationPermissions.MOBILE_UNIT_MANAGE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:mobile-unit:manage"})
    @PatchMapping("/{id}")
    public ResponseEntity<MobileUnitResponse> patchMobileUnit(
            @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Free-form patch object; only the keys name, status, notes and"
                                    + " travelBufferPolicyId are recognized.",
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

    @Operation(
            operationId = "replaceCoverageRules",
            summary = "Replace Coverage Rules for Mobile Unit",
            description = """
                    Atomically replaces the full set of coverage rules for a mobile unit, deleting the existing \
                    rules and inserting the supplied ones in one transaction.
                    Use this tool whenever coverage changes, sending the complete desired rule set; do not use \
                    patchMobileUnit, which cannot modify coverage.
                    Preconditions: the mobile unit must exist; a referenced serviceAreaId that does not resolve \
                    is stored as a rule without a service area rather than rejected.
                    Required inputs: id (UUID) as a path parameter and a body of the form {"rules": [...]}, each \
                    rule carrying ruleType and optionally serviceAreaId, priority (defaults to 0), validFrom, \
                    validTo and maxDistance.
                    Emits a LOCATION_COVERAGE_RULES_REPLACE event.
                    Returns 404 when the mobile unit does not exist; an omitted or empty rules array clears all \
                    coverage.
                    """)
    @ApiResponse(responseCode = "200", description = "Coverage rules replaced successfully.")
    @ApiResponse(responseCode = "404", description = "Mobile unit not found.")
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
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawRules = (List<Map<String, Object>>) payload.getOrDefault("rules", List.of());
        return ResponseEntity.ok(mobileUnitService.replaceCoverageRules(id.toString(), rawRules));
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
