package com.positivity.marketing.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.marketing.internal.dto.CampaignStatsResponse;
import com.positivity.marketing.internal.dto.ProgramStatsResponse;
import com.positivity.marketing.internal.security.MarketingPermissionRegistry;
import com.positivity.marketing.service.CampaignStatsService;
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

    @Operation(
            summary = "Campaign stats",
            description = "Per-channel reach funnel plus redemptions and discount value credited to the campaign")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Stats returned",
                content = @Content(schema = @Schema(implementation = CampaignStatsResponse.class))),
        @ApiResponse(responseCode = "404", description = "Campaign not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
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

    @Operation(
            summary = "Program stats",
            description = "Compare the commercial and individual arms of one campaign program side by side")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Program stats returned",
                content = @Content(schema = @Schema(implementation = ProgramStatsResponse.class))),
        @ApiResponse(responseCode = "404", description = "Campaign program not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
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
