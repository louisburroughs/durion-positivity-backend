package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import com.positivity.shopmanager.internal.dto.LocationTechnicianRosterEntryResponse;
import com.positivity.shopmanager.internal.dto.PersonDTO;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.security.ShopPermissions;
import com.positivity.shopmanager.internal.service.MechanicRosterQueryService;
import com.positivity.shopmanager.internal.service.TechnicianPersonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Technician API", description = "Technician identity lookups for a shop location")
@RestController
@RequestMapping("/v1/shop-manager")
@RequiredArgsConstructor
public class TechnicianController {

    private static final String LOCATION_SCOPE_DENIED_DESCRIPTION =
            "Caller holds shop:technician:view but its location scope does not cover the requested location"
                    + " (ApiError.code LOCATION_SCOPE_DENIED, see docs/ERROR_ENVELOPE.md)";

    private final TechnicianPersonService technicianPersonService;
    private final MechanicRosterQueryService mechanicRosterQueryService;

    @Operation(
            operationId = "listLocationTechnicians",
            summary = "List technicians assigned to a location",
            description = """
                    Returns the technicians assigned to one shop location, enriched with mechanic identity and \
                    skills from the eventually consistent HR read model.
                    Use this tool when staffing or dispatching work at a single location; use listMechanics instead \
                    for the shop-wide roster, and getTechnicianPerson instead for one technician's contact details.
                    Preconditions: the location must exist as a shop, and both the technician assignments and their \
                    mechanic projection must have arrived over Kafka.
                    Required inputs: locationId (UUID) as a path parameter, and there is no request body; optionally \
                    narrow with status and skillCode, both exact matches, where an omitted status defaults to ACTIVE, \
                    and page with page and size, since sort is accepted but ignored and the location roster is \
                    returned in a fixed order.
                    Emits a SHOPMGR_LOCATION_TECHNICIAN_LIST audit event; no state changes occur, and the enrichment \
                    trails the People/HR authority by the event-propagation delay.
                    A caller whose shop:technician:view grant is location-scoped must have locationId within \
                    reach (ADR-0061).
                    Returns 404 when no shop exists for the location id, 403 FORBIDDEN when the caller lacks \
                    shop:technician:view, 403 LOCATION_SCOPE_DENIED when the caller's location scope does not \
                    cover locationId, and an empty page rather than an error when no technician matches the \
                    filters.
                    """)
    @ApiResponse(responseCode = "200", description = "Location technician roster page returned.")
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks technician roster permission (ApiError.code FORBIDDEN), or "
                    + LOCATION_SCOPE_DENIED_DESCRIPTION,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Shop location not found.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "SHOPMGR_LOCATION_TECHNICIAN_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"shop:technician:view"})
    @PreAuthorize("hasAuthority('" + ShopPermissions.TECHNICIAN_VIEW + "')")
    @GetMapping("/{locationId}/technicians")
    public ResponseEntity<PagedModel<LocationTechnicianRosterEntryResponse>> listLocationTechnicians(
            @Parameter(description = "Shop location ID") @PathVariable UUID locationId,
            @RequestParam(required = false) MechanicStatus status,
            @RequestParam(required = false) String skillCode,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        // locationId names the roster being read; a scoped caller must have it in reach
        // (ADR-0061 §3, #1872). Spring has already rejected a malformed id with a 400.
        SecurityContextHelper.locationScope().require(ShopPermissions.TECHNICIAN_VIEW, locationId);
        return ResponseEntity.ok(new PagedModel<>(
                mechanicRosterQueryService.listLocationTechnicians(locationId, status, skillCode, pageable)));
    }

    @Operation(operationId = "getTechnicianPerson", summary = "Get Person Details for a Technician", description = """
                                        Resolves the person identity (names, emails, phone numbers) of a technician working at a shop \
                                        location from the local people-contact replica.
                                        Use this tool when displaying or contacting an assigned technician; use viewSchedule instead \
                                        for the technician's scheduled work.
                                        Preconditions: a technician record must link the personId to the locationId; replica identity \
                                        fields can trail the people-contact authority by the event-propagation delay.
                                        Required inputs: locationId and personId (UUIDs) as path parameters; there is no request body.
                                        Emits a SHOPMGR_TECHNICIAN_PERSON_GET audit event; no state changes occur, and when the \
                                        replica row has not yet arrived the response carries only the person id with name and contact \
                                        fields null.
                                        A caller whose shop:technician:view grant is location-scoped must have locationId within \
                                        reach (ADR-0061).
                                        Returns 403 LOCATION_SCOPE_DENIED when the caller's location scope does not cover \
                                        locationId, and 404 when no technician links the person to the location.
                                        """)
    @ApiResponse(responseCode = "200", description = "Technician person details returned.")
    @ApiResponse(
            responseCode = "403",
            description = LOCATION_SCOPE_DENIED_DESCRIPTION,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No technician links this person to this location.")
    @EmitEvent(id = "SHOPMGR_TECHNICIAN_PERSON_GET", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"shop:technician:view"})
    @PreAuthorize("hasAuthority('" + ShopPermissions.TECHNICIAN_VIEW + "')")
    @GetMapping("/{locationId}/technicians/{personId}/person")
    public ResponseEntity<PersonDTO> getTechnicianPerson(
            @Parameter(description = "Shop location ID", example = "018e1c9f-6b5a-7890-abcd-1234567890ab") @PathVariable
                    UUID locationId,
            @Parameter(description = "People-contact person ID", example = "01960011-0000-7000-8000-000000000001")
                    @PathVariable
                    UUID personId) {
        // The path locationId is the boundary; a scoped caller must have it in reach before the
        // person lookup runs (ADR-0061 §3, #1872).
        SecurityContextHelper.locationScope().require(ShopPermissions.TECHNICIAN_VIEW, locationId);
        return ResponseEntity.ok(technicianPersonService.getTechnicianPerson(locationId, personId));
    }
}
