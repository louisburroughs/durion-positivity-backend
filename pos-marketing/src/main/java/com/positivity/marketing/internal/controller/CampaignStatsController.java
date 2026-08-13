package com.positivity.marketing.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.marketing.internal.dto.CampaignStatsResponse;
import com.positivity.marketing.internal.dto.ProgramStatsResponse;
import com.positivity.marketing.internal.security.MarketingPermissionRegistry;
import com.positivity.marketing.service.CampaignStatsService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Campaign reach funnel and redemption attribution (Story #1152). */
@Tag(name = "Marketing Analytics", description = "Campaign reach, delivery, and attribution analytics")
@RestController
@RequestMapping("/v1/marketing")
public class CampaignStatsController {

    private final CampaignStatsService statsService;

    public CampaignStatsController(CampaignStatsService statsService) {
        this.statsService = statsService;
    }

    @Operation(operationId = "getCampaignStats", summary = "Get Campaign Stats", description = """
                    Returns one campaign's per-channel delivery funnel (targeted, suppressed, sent, \
                    delivered, bounced, complained, failed) plus the redemption count and discount value \
                    attributed to its campaign code; the sent figure is cumulative, counting delivered, \
                    bounced and complained messages as sent.
                    Use this tool for aggregate campaign performance; use listCampaignSends instead to \
                    inspect individual recipient send rows, and use getProgramStats to compare the arms of a \
                    program.
                    Preconditions: the campaign must exist; a campaign that has never dispatched reports \
                    zeroed funnels rather than an error.
                    Required inputs: campaignId (UUID) as a path parameter; there is no request body.
                    Emits a MARKETING_CAMPAIGN_STATS_GET audit event; no marketing state is changed and this \
                    is a read-only projection.
                    Returns 404 when no campaign exists for the supplied id.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Stats returned",
                content = @Content(schema = @Schema(implementation = CampaignStatsResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Campaign not found",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Forbidden - insufficient permissions",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/campaigns/{campaignId}/stats")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.STATS_VIEW})
    @PreAuthorize("hasAuthority('marketing:stats:view')")
    @EmitEvent(id = "MARKETING_CAMPAIGN_STATS_GET", apiVersion = "1")
    public ResponseEntity<CampaignStatsResponse> campaignStats(@PathVariable UUID campaignId) {
        return ResponseEntity.ok(statsService.campaignStats(campaignId));
    }

    @Operation(operationId = "getProgramStats", summary = "Get Campaign Program Stats", description = """
                    Returns the full stats block for every campaign sharing one campaignProgramId, so the \
                    commercial and individual arms of an initiative can be compared side by side.
                    Use this tool when comparing the arms of a program; use getCampaignStats instead for a \
                    single campaign.
                    Preconditions: at least one campaign must reference the campaignProgramId, since the \
                    program has no record of its own in this module.
                    Required inputs: campaignProgramId (UUID) as a path parameter; there is no request body.
                    Emits a MARKETING_PROGRAM_STATS_GET audit event; no marketing state is changed and this \
                    is a read-only projection.
                    Returns 404 when no campaign references the supplied campaignProgramId.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Program stats returned",
                content = @Content(schema = @Schema(implementation = ProgramStatsResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Campaign program not found",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Forbidden - insufficient permissions",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/programs/{campaignProgramId}/stats")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.STATS_VIEW})
    @PreAuthorize("hasAuthority('marketing:stats:view')")
    @EmitEvent(id = "MARKETING_PROGRAM_STATS_GET", apiVersion = "1")
    public ResponseEntity<ProgramStatsResponse> programStats(@PathVariable UUID campaignProgramId) {
        return ResponseEntity.ok(statsService.programStats(campaignProgramId));
    }
}
