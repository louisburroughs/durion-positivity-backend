package com.positivity.marketing.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.marketing.internal.dto.AudiencePreviewResponse;
import com.positivity.marketing.internal.dto.CampaignResponse;
import com.positivity.marketing.internal.dto.CampaignSendResponse;
import com.positivity.marketing.internal.dto.PagedResponse;
import com.positivity.marketing.internal.dto.UpsertCampaignRequest;
import com.positivity.marketing.internal.enums.CampaignStatus;
import com.positivity.marketing.internal.security.MarketingPermissionRegistry;
import com.positivity.marketing.service.CampaignSendService;
import com.positivity.marketing.service.CampaignService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Campaign definition, lifecycle commands, audience preview, and dispatch (Stories #1146/#1148/#1149). */
@Tag(name = "Marketing Campaigns", description = "Campaign definition, lifecycle, audience preview, and dispatch")
@RestController
@RequestMapping("/v1/marketing/campaigns")
public class CampaignController {

    private final CampaignService campaignService;
    private final CampaignSendService campaignSendService;

    public CampaignController(CampaignService campaignService, CampaignSendService campaignSendService) {
        this.campaignService = campaignService;
        this.campaignSendService = campaignSendService;
    }

    @Operation(summary = "List campaigns", description = "List campaigns, optionally filtered by status or program")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Campaigns returned",
                content =
                        @Content(
                                mediaType = "application/json",
                                array = @ArraySchema(schema = @Schema(implementation = CampaignResponse.class)))),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.CAMPAIGN_VIEW})
    @PreAuthorize("hasAuthority('marketing:campaign:view')")
    @EmitEvent(id = "MARKETING_CAMPAIGN_LIST", apiVersion = "1")
    public ResponseEntity<List<CampaignResponse>> list(
            @RequestParam(name = "status", required = false) CampaignStatus status,
            @RequestParam(name = "campaignProgramId", required = false) UUID campaignProgramId) {
        return ResponseEntity.ok(campaignService.list(status, campaignProgramId));
    }

    @Operation(summary = "Get campaign", description = "Retrieve a campaign by id")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Campaign returned",
                content = @Content(schema = @Schema(implementation = CampaignResponse.class))),
        @ApiResponse(responseCode = "404", description = "Campaign not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping("/{campaignId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.CAMPAIGN_VIEW})
    @PreAuthorize("hasAuthority('marketing:campaign:view')")
    @EmitEvent(id = "MARKETING_CAMPAIGN_GET", apiVersion = "1")
    public ResponseEntity<CampaignResponse> get(@PathVariable UUID campaignId) {
        return ResponseEntity.ok(campaignService.get(campaignId));
    }

    @Operation(summary = "Create campaign", description = "Create a campaign in DRAFT status")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Campaign created",
                content = @Content(schema = @Schema(implementation = CampaignResponse.class))),
        @ApiResponse(responseCode = "409", description = "Campaign code already exists", content = @Content),
        @ApiResponse(responseCode = "422", description = "Campaign definition is inconsistent", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.CAMPAIGN_CREATE})
    @PreAuthorize("hasAuthority('marketing:campaign:create')")
    @EmitEvent(id = "MARKETING_CAMPAIGN_CREATE", apiVersion = "1")
    public ResponseEntity<CampaignResponse> create(@Valid @RequestBody UpsertCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(campaignService.create(request));
    }

    @Operation(
            summary = "Update campaign",
            description = "Update a DRAFT campaign. Editing is refused once dispatch has begun.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Campaign updated",
                content = @Content(schema = @Schema(implementation = CampaignResponse.class))),
        @ApiResponse(responseCode = "404", description = "Campaign not found", content = @Content),
        @ApiResponse(responseCode = "409", description = "Campaign code already exists", content = @Content),
        @ApiResponse(
                responseCode = "422",
                description = "Campaign is no longer editable, or an immutable field was changed",
                content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PutMapping("/{campaignId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.CAMPAIGN_EDIT})
    @PreAuthorize("hasAuthority('marketing:campaign:edit')")
    @EmitEvent(id = "MARKETING_CAMPAIGN_UPDATE", apiVersion = "1")
    public ResponseEntity<CampaignResponse> update(
            @PathVariable UUID campaignId, @Valid @RequestBody UpsertCampaignRequest request) {
        return ResponseEntity.ok(campaignService.update(campaignId, request));
    }

    @Operation(
            summary = "Preview audience",
            description =
                    "Per-channel reach after consent, account gate, and suppression, plus any issues that would block scheduling")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Preview returned",
                content = @Content(schema = @Schema(implementation = AudiencePreviewResponse.class))),
        @ApiResponse(responseCode = "422", description = "Campaign has no segment bound", content = @Content),
        @ApiResponse(responseCode = "503", description = "pos-customer unavailable", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping("/{campaignId}/audience-preview")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.CAMPAIGN_VIEW})
    @PreAuthorize("hasAuthority('marketing:campaign:view')")
    @EmitEvent(id = "MARKETING_AUDIENCE_PREVIEW", apiVersion = "1")
    public ResponseEntity<AudiencePreviewResponse> previewAudience(@PathVariable UUID campaignId) {
        return ResponseEntity.ok(campaignService.previewAudience(campaignId));
    }

    @Operation(
            summary = "Schedule campaign",
            description = "Validate the segment, templates, and offer, then move the campaign to SCHEDULED")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Campaign scheduled",
                content = @Content(schema = @Schema(implementation = CampaignResponse.class))),
        @ApiResponse(responseCode = "404", description = "Campaign not found", content = @Content),
        @ApiResponse(responseCode = "422", description = "Campaign is not ready to schedule", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping("/{campaignId}/schedule")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.CAMPAIGN_SCHEDULE})
    @PreAuthorize("hasAuthority('marketing:campaign:schedule')")
    @EmitEvent(id = "MARKETING_CAMPAIGN_SCHEDULE", apiVersion = "1")
    public ResponseEntity<CampaignResponse> schedule(@PathVariable UUID campaignId) {
        return ResponseEntity.ok(campaignService.schedule(campaignId));
    }

    @Operation(summary = "Pause campaign", description = "Halt dispatch; queued recipients stop being drained")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Campaign paused",
                content = @Content(schema = @Schema(implementation = CampaignResponse.class))),
        @ApiResponse(responseCode = "422", description = "Illegal lifecycle transition", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping("/{campaignId}/pause")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.CAMPAIGN_MANAGE})
    @PreAuthorize("hasAuthority('marketing:campaign:manage')")
    @EmitEvent(id = "MARKETING_CAMPAIGN_PAUSE", apiVersion = "1")
    public ResponseEntity<CampaignResponse> pause(@PathVariable UUID campaignId) {
        return ResponseEntity.ok(campaignService.pause(campaignId));
    }

    @Operation(summary = "Resume campaign", description = "Return a paused campaign to the dispatch queue")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Campaign resumed",
                content = @Content(schema = @Schema(implementation = CampaignResponse.class))),
        @ApiResponse(responseCode = "422", description = "Illegal lifecycle transition", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping("/{campaignId}/resume")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.CAMPAIGN_MANAGE})
    @PreAuthorize("hasAuthority('marketing:campaign:manage')")
    @EmitEvent(id = "MARKETING_CAMPAIGN_RESUME", apiVersion = "1")
    public ResponseEntity<CampaignResponse> resume(@PathVariable UUID campaignId) {
        return ResponseEntity.ok(campaignService.resume(campaignId));
    }

    @Operation(summary = "Cancel campaign", description = "Cancel a campaign; the state is terminal")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Campaign cancelled",
                content = @Content(schema = @Schema(implementation = CampaignResponse.class))),
        @ApiResponse(responseCode = "422", description = "Illegal lifecycle transition", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping("/{campaignId}/cancel")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.CAMPAIGN_MANAGE})
    @PreAuthorize("hasAuthority('marketing:campaign:manage')")
    @EmitEvent(id = "MARKETING_CAMPAIGN_CANCEL", apiVersion = "1")
    public ResponseEntity<CampaignResponse> cancel(@PathVariable UUID campaignId) {
        return ResponseEntity.ok(campaignService.cancel(campaignId));
    }

    @Operation(
            summary = "Dispatch campaign",
            description =
                    "Queue the campaign's audience for delivery. Asynchronous and idempotent: re-invoking never double-sends.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Dispatch accepted"),
        @ApiResponse(responseCode = "404", description = "Campaign not found", content = @Content),
        @ApiResponse(responseCode = "422", description = "Campaign is not in a dispatchable state", content = @Content),
        @ApiResponse(responseCode = "503", description = "pos-customer unavailable", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping("/{campaignId}/send")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.CAMPAIGN_SEND})
    @PreAuthorize("hasAuthority('marketing:campaign:send')")
    @EmitEvent(id = "MARKETING_CAMPAIGN_SEND", apiVersion = "1")
    public ResponseEntity<Map<String, Object>> send(@PathVariable UUID campaignId) {
        int queued = campaignSendService.dispatch(campaignId);
        return ResponseEntity.accepted().body(Map.of("campaignId", campaignId, "queued", queued));
    }

    @Operation(summary = "List sends", description = "Per-recipient send records for a campaign")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Send records returned",
                content = @Content(schema = @Schema(implementation = PagedResponse.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping("/{campaignId}/sends")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.CAMPAIGN_VIEW})
    @PreAuthorize("hasAuthority('marketing:campaign:view')")
    @EmitEvent(id = "MARKETING_CAMPAIGN_SENDS_LIST", apiVersion = "1")
    public ResponseEntity<PagedResponse<CampaignSendResponse>> listSends(
            @PathVariable UUID campaignId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(campaignSendService.listSends(campaignId, page, size));
    }
}
