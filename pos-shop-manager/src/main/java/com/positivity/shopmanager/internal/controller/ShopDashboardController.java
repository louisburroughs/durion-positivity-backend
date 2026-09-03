package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.shopmanager.internal.dto.ShopDashboardResponse;
import com.positivity.shopmanager.internal.security.ShopPermissions;
import com.positivity.shopmanager.internal.service.ShopDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Single-call read model behind the shop manager dashboard (#1658). */
@Tag(name = "Shop Dashboard API", description = "Aggregate read model for the shop manager dashboard")
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ShopDashboardController {

    private final ShopDashboardService shopDashboardService;

    @Operation(operationId = "getShopDashboard", summary = "Get the shop dashboard for a location", description = """
                    Returns every bay and mobile unit at one shop location together with the workorder occupying \
                    each, and every open workorder at that location, in a single read.
                    Use this tool to render or refresh a shop manager dashboard; use listLocationTechnicians \
                    instead when only the staffing roster is needed, and do not call it once per unit or per \
                    workorder, because one call already returns the whole board.
                    Preconditions: the location must exist as a shop, and the bay, mobile-unit, workorder, vehicle \
                    and person facts are replicated from their owning domains over Kafka, so an assignment made \
                    moments ago can be a few seconds behind.
                    Required inputs: locationId (UUID) as a query parameter; date (yyyy-MM-dd) is optional and \
                    defaults to the location's local today, and it scopes only the unit roster, never \
                    openWorkorders.
                    Emits a SHOPMGR_SHOP_DASHBOARD_VIEW audit event; no state changes occur, openWorkorders is \
                    capped at 200 rows with openWorkordersTruncated set when the cap is hit, and a unit holding no \
                    work is returned with a null assignment rather than omitted.
                    Returns 400 when locationId or date is malformed, 403 when the caller lacks \
                    shop:dashboard:view, and 404 when no shop exists for the location id.
                    """)
    @ApiResponse(responseCode = "200", description = "Shop dashboard returned.")
    @ApiResponse(
            responseCode = "400",
            description = "locationId or date is not a valid value.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the shop dashboard view permission.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Shop location not found.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "SHOPMGR_SHOP_DASHBOARD_VIEW", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {ShopPermissions.DASHBOARD_VIEW})
    @PreAuthorize("hasAuthority('" + ShopPermissions.DASHBOARD_VIEW + "')")
    @GetMapping("/shop-dashboard")
    public ResponseEntity<ShopDashboardResponse> getShopDashboard(
            @Parameter(
                            name = "locationId",
                            description = "Shop location identifier in UUID format. Must be an existing shop.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018e1c9f-6b5a-7890-abcd-1234567890ab"))
                    @RequestParam
                    UUID locationId,
            @Parameter(
                            name = "date",
                            description = "Day the unit roster is rendered as of, as a date-only yyyy-MM-dd string"
                                    + " (ADR-0038). Defaults to the location's local today and never scopes"
                                    + " openWorkorders.",
                            required = false,
                            schema = @Schema(type = "string", format = "date", example = "2026-09-03"))
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        return ResponseEntity.ok(shopDashboardService.getDashboard(locationId, date));
    }
}
