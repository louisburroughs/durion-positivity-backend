package com.positivity.location.internal.controller;

import com.positivity.location.internal.dto.EligibleMobileUnitResponse;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.MobileUnitService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dedicated eligibility endpoint controller for exact route binding.
 *
 * Issue: #76
 */
@RestController
@SecurityRequirement(
        name = "bearerAuth",
        scopes = {"location:mobile-unit:read"})
@RequiredArgsConstructor
public class MobileUnitEligibilityController {

    private final MobileUnitService mobileUnitService;

    @Operation(
            operationId = "findEligibleMobileUnits",
            summary = "Find Eligible Mobile Units for Address and Base Location",
            description = """
                    Finds the ACTIVE mobile units based at baseLocationId whose coverage rules include a postal \
                    code at a given instant, ordered by priority ascending and then by unit id, one deterministic \
                    ranking across that location's units (DECISION-LOCATION-027).
                    Use this tool when dispatching a mobile service request to a customer address at a known \
                    location; use listMobileUnits instead for plain enumeration without eligibility matching.
                    Preconditions: coverage rules must already link units to service areas containing the postal \
                    code; only rules on an active service area match, units whose status is not ACTIVE are \
                    excluded, and a unit only takes work from its own base location \
                    (DECISION-SHOPMGMT-023 rule 1).
                    Required inputs: postalCode, countryCode, at (an ISO-8601 instant) and baseLocationId, all \
                    mandatory; validFrom/validTo are matched against at as UTC instants, validFrom inclusive and \
                    validTo exclusive. operationCodes is optional and, when sent, a unit must claim every code \
                    listed (units have no general-work default).
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the eligible units, empty when nothing covers the address on that instant, \
                    400 VALIDATION_ERROR with fieldErrors naming baseLocationId when it is missing, and 403 \
                    LOCATION_SCOPE_DENIED when baseLocationId is outside a location-scoped caller's reach \
                    (ADR-0061, gated the same way as listMobileUnits' baseLocationId filter).
                    """)
    @ApiResponse(responseCode = "200", description = "Eligible mobile units returned.")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: baseLocationId is missing or not a UUID.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks location:mobile-unit:read, or (LOCATION_SCOPE_DENIED) the named"
                    + " baseLocationId is outside a location-scoped caller's reach.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + LocationPermissions.MOBILE_UNIT_READ + "')")
    @GetMapping("/v1/mobile-units:eligible")
    public ResponseEntity<List<EligibleMobileUnitResponse>> findEligibleMobileUnits(
            @RequestParam String postalCode,
            @RequestParam String countryCode,
            @RequestParam Instant at,
            @Parameter(
                            description = "Only units based at this location",
                            required = true,
                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01")
                    @RequestParam(required = false)
                    UUID baseLocationId,
            @Parameter(
                            description = "Operation codes the unit must claim every one of; optional",
                            example = "OIL-CHANGE-FULL-SYNTHETIC")
                    @RequestParam(required = false)
                    List<String> operationCodes) {
        // baseLocationId names the shop whose units are offered, gated the same way
        // BayController.listBays and MobileUnitController.listMobileUnits gate their own
        // baseLocationId/locationId filter (ADR-0061, location-scope.yaml, PR #2278 MEDIUM). Left
        // conditional on non-null so a caller who omits it still gets the service's own 400
        // VALIDATION_ERROR rather than a 403 for a parameter that was never supplied.
        if (baseLocationId != null) {
            SecurityContextHelper.locationScope().require(LocationPermissions.MOBILE_UNIT_READ, baseLocationId);
        }
        return ResponseEntity.ok(mobileUnitService.findEligibleMobileUnits(
                postalCode, countryCode, at, baseLocationId, normalizeOperationCodes(operationCodes)));
    }

    /** {@code operationCodes} is a comma-separated or repeated list, like {@code include} elsewhere. */
    private static List<String> normalizeOperationCodes(List<String> operationCodes) {
        if (operationCodes == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String entry : operationCodes) {
            for (String value : entry.split(",")) {
                if (!value.isBlank()) {
                    values.add(value.trim());
                }
            }
        }
        return values;
    }
}
