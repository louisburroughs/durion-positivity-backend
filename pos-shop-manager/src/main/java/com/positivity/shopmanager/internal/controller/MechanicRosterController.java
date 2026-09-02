package com.positivity.shopmanager.internal.controller;

import com.positivity.shopmanager.internal.dto.MechanicRosterEntryResponse;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.security.ShopPermissions;
import com.positivity.shopmanager.internal.service.MechanicRosterQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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

    @Operation(
            operationId = "listMechanics",
            summary = "List mechanics",
            description = "Returns the eventually consistent mechanic read model synchronized from People/HR.")
    @ApiResponse(responseCode = "200", description = "Mechanic roster page returned.")
    @ApiResponse(responseCode = "403", description = "Caller lacks technician roster permission.")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"shop:technician:view"})
    @PreAuthorize("hasAuthority('" + ShopPermissions.TECHNICIAN_VIEW + "')")
    @GetMapping("/mechanics")
    public ResponseEntity<PagedModel<MechanicRosterEntryResponse>> listMechanics(
            @RequestParam(required = false) MechanicStatus status,
            @RequestParam(required = false) String skillCode,
            @PageableDefault(
                            size = 20,
                            sort = {"lastName", "firstName", "personId"})
                    Pageable pageable) {
        return ResponseEntity.ok(
                new PagedModel<>(mechanicRosterQueryService.listMechanics(status, skillCode, pageable)));
    }
}
