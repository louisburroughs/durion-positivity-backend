package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.shopmanager.internal.dto.MechanicRosterEntryResponse;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.security.ShopPermissions;
import com.positivity.shopmanager.internal.service.MechanicRosterQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Mechanic Roster API", description = "Read-only HR-synchronized mechanic roster queries")
@RestController
@RequestMapping("/v1/shop-manager")
@RequiredArgsConstructor
public class MechanicRosterController {

    private final MechanicRosterQueryService mechanicRosterQueryService;

    @Operation(operationId = "listMechanics", summary = "List mechanics", description = """
                    Returns the eventually consistent mechanic read model synchronized from People/HR, one page \
                    at a time.
                    Use this tool when building a shop-wide mechanic picker or capability report; use \
                    listLocationTechnicians instead when the roster must be scoped to a single shop location, and \
                    getTechnicianPerson for one technician's contact details.
                    Preconditions: mechanics are projected from ACTIVE TECHNICIAN staffing assignments over Kafka, \
                    so a newly hired mechanic appears only once that projection has caught up.
                    Required inputs: none, and there is no request body; optionally narrow with status and skillCode, \
                    both exact matches, where an omitted status defaults to ACTIVE, and page with the standard page, \
                    size and sort parameters, which default to a stable lastName, firstName, personId ordering.
                    Emits a SHOPMGR_MECHANIC_ROSTER_LIST audit event; no state changes occur, and rows trail the \
                    People/HR authority by the event-propagation delay.
                    Returns 403 when the caller lacks shop:technician:view, and an empty page rather than an error \
                    when no mechanic matches the filters.
                    """)
    @ApiResponse(responseCode = "200", description = "Mechanic roster page returned.")
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks technician roster permission.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "SHOPMGR_MECHANIC_ROSTER_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"shop:technician:view"})
    @PreAuthorize("hasAuthority('" + ShopPermissions.TECHNICIAN_VIEW + "')")
    @GetMapping("/mechanics")
    public ResponseEntity<PagedModel<MechanicRosterEntryResponse>> listMechanics(
            @RequestParam(required = false) MechanicStatus status,
            @RequestParam(required = false) String skillCode,
            @ParameterObject
                    @PageableDefault(
                            size = 20,
                            sort = {"lastName", "firstName", "personId"})
                    Pageable pageable) {
        return ResponseEntity.ok(
                new PagedModel<>(mechanicRosterQueryService.listMechanics(status, skillCode, pageable)));
    }
}
