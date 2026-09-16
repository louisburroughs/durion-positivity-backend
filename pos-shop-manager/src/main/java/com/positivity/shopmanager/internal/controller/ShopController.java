package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import com.positivity.shopmanager.internal.dto.ShopResponse;
import com.positivity.shopmanager.internal.dto.ShopUpsertRequest;
import com.positivity.shopmanager.internal.security.ShopPermissions;
import com.positivity.shopmanager.internal.service.ShopConfigurationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Shop API", description = "Scheduling configuration that makes a location schedulable")
@RestController
@RequestMapping("/v1/shops")
@RequiredArgsConstructor
public class ShopController {

    private final ShopConfigurationService shopConfigurationService;

    @Operation(
            operationId = "upsertShop",
            summary = "Create or Replace a Location's Shop Configuration",
            description = """
                    Creates the shop record that makes a location schedulable, or replaces its configuration \
                    when one already exists.
                    Use this tool to make a site bookable for the first time or to correct its name, address or \
                    scheduling timezone; use viewSchedule to read what is booked, which answers 404 for any \
                    location that has no shop record.
                    Preconditions: none beyond the id — the shop carries the pos-location location id by \
                    convention, which is how every other read in this module resolves a request's locationId, \
                    and this operation does not verify that the location exists.
                    Required inputs: locationId (UUID) as a path parameter and a body with a non-blank name; \
                    address is optional, and timezone is optional but must be a valid IANA zone id when supplied, \
                    because the schedule view computes the day window in it and falls back to UTC when it is unset.
                    Emits a SHOPMGR_SHOP_UPSERT audit event and writes only the shop row; no other records are \
                    touched and no fact is published.
                    Idempotent on locationId, so a reseed converges rather than duplicating.
                    Returns 400 when the name is blank or the timezone is not a zone id, and 403 when the caller \
                    lacks shop:schedule:edit.
                    """)
    @ApiResponse(responseCode = "200", description = "Shop configuration created or replaced.")
    @ApiResponse(
            responseCode = "400",
            description = "Blank name or invalid timezone.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks schedule edit permission.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "SHOPMGR_SHOP_UPSERT", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"shop:schedule:edit"})
    @PreAuthorize("hasAuthority('" + ShopPermissions.SCHEDULE_EDIT + "')")
    @PutMapping("/{locationId}")
    public ResponseEntity<ShopResponse> upsertShop(
            @PathVariable UUID locationId, @Valid @RequestBody ShopUpsertRequest request) {
        // locationId names the site being configured; a scoped caller must have it in reach
        // (ADR-0061 §3, #1872). Without this a caller scoped to one site could make any other
        // site schedulable, or retime its day. Spring has already rejected a malformed id.
        SecurityContextHelper.locationScope().require(ShopPermissions.SCHEDULE_EDIT, locationId);
        return ResponseEntity.ok(shopConfigurationService.upsert(locationId, request));
    }
}
