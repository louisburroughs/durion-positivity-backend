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
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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

    static final String CAMPAIGN_EXAMPLE = """
            {"code":"SPRING-FLEET-2026",
             "name":"Spring fleet alignment push",
             "description":"Alignment offer for commercial fleet accounts",
             "audienceType":"COMMERCIAL",
             "campaignProgramId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
             "channels":["EMAIL","SMS"],
             "segmentId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02",
             "promotionOfferId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03",
             "catalogFocusRef":"service:alignment",
             "windowStart":"2026-03-01T00:00:00Z",
             "windowEnd":"2026-03-31T23:59:59Z",
             "scheduleType":"SCHEDULED",
             "scheduledAt":"2026-03-02T14:00:00Z",
             "emailTemplateId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a04",
             "smsTemplateId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a05"}
            """;

    private final CampaignService campaignService;
    private final CampaignSendService campaignSendService;

    public CampaignController(CampaignService campaignService, CampaignSendService campaignSendService) {
        this.campaignService = campaignService;
        this.campaignSendService = campaignSendService;
    }

    @Operation(operationId = "listCampaigns", summary = "List Campaigns", description = """
                    Lists marketing campaigns ordered by newest first, optionally filtered by lifecycle status \
                    and campaign program.
                    Use this tool when browsing or searching campaigns; do not use getCampaignById, which \
                    requires a known campaign id and returns exactly one campaign.
                    Preconditions: none; an empty result simply means no campaign matches the filters.
                    Required inputs: none; status (DRAFT, SCHEDULED, SENDING, SENT, PAUSED, CANCELLED or \
                    CLOSED) and campaignProgramId (UUID) are optional query filters that combine when both \
                    are supplied.
                    Emits a MARKETING_CAMPAIGN_LIST audit event; no marketing state is changed.
                    Returns 200 with an empty array when nothing matches, so an empty result is not an error \
                    condition.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Campaigns returned",
                content =
                        @Content(
                                mediaType = "application/json",
                                array = @ArraySchema(schema = @Schema(implementation = CampaignResponse.class)))),
        @ApiResponse(
                responseCode = "403",
                description = "Forbidden - insufficient permissions",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
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

    @Operation(operationId = "getCampaignById", summary = "Get Campaign By Id", description = """
                    Returns one campaign's full definition, including status, channels, bound segment, \
                    templates, schedule and delivery window.
                    Use this tool when the campaign id is already known; use listCampaigns instead when \
                    searching by status or program.
                    Preconditions: the campaign must exist.
                    Required inputs: campaignId (UUID) as a path parameter; there is no request body.
                    Emits a MARKETING_CAMPAIGN_GET audit event; no marketing state is changed and this is a \
                    read-only projection.
                    Returns 404 when no campaign exists for the supplied id.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Campaign returned",
                content = @Content(schema = @Schema(implementation = CampaignResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Campaign not found",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Forbidden - insufficient permissions",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
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

    @Operation(operationId = "createCampaign", summary = "Create Campaign", description = """
                    Creates a marketing campaign in DRAFT status; nothing is targeted or sent until it is \
                    scheduled and dispatched.
                    Use this tool when defining a new campaign; do not use updateCampaign, which edits an \
                    existing DRAFT campaign.
                    Preconditions: no campaign may already use the same code (compared case-insensitively); \
                    segment and templates may be attached later, since readiness is only enforced by \
                    scheduleCampaign.
                    Required inputs: code, name, audienceType (COMMERCIAL or INDIVIDUAL, immutable after \
                    creation) and a non-empty channels set (EMAIL, SMS); scheduleType defaults to IMMEDIATE, \
                    and scheduledAt is required only when scheduleType is SCHEDULED.
                    Emits a MARKETING_CAMPAIGN_CREATE event; no recipients are contacted.
                    Returns 409 when the code is already in use, and 422 when scheduleType is SCHEDULED \
                    without scheduledAt or windowEnd precedes windowStart.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Campaign created",
                content = @Content(schema = @Schema(implementation = CampaignResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Campaign code already exists",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "422",
                description = "Campaign definition is inconsistent",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Forbidden - insufficient permissions",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.CAMPAIGN_CREATE})
    @PreAuthorize("hasAuthority('marketing:campaign:create')")
    @EmitEvent(id = "MARKETING_CAMPAIGN_CREATE", apiVersion = "1")
    public ResponseEntity<CampaignResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Campaign definition: code, name, audience, channels, and the optional segment,"
                                            + " templates, offer and schedule to attach.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Scheduled commercial campaign",
                                                            value = CAMPAIGN_EXAMPLE)))
                    @Valid
                    @RequestBody
                    UpsertCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(campaignService.create(request));
    }

    @Operation(operationId = "updateCampaign", summary = "Update Draft Campaign", description = """
                    Replaces the definition of a DRAFT campaign, including its code, channels, segment, \
                    templates, schedule and delivery window.
                    Use this tool to revise a campaign before it is scheduled; do not use createCampaign, \
                    which makes a new campaign, and use the lifecycle tools (scheduleCampaign, pauseCampaign, \
                    cancelCampaign) rather than this one to change status.
                    Preconditions: the campaign must exist and still be in DRAFT, since once dispatch may \
                    have begun the definition is frozen; audienceType must equal the stored value because it \
                    is immutable.
                    Required inputs: campaignId (UUID) path parameter plus the full replacement body (code, \
                    name, audienceType, channels); scheduleType defaults to IMMEDIATE when omitted, and \
                    omitted optional fields are cleared rather than preserved.
                    Emits a MARKETING_CAMPAIGN_UPDATE event; no recipients are contacted.
                    Returns 404 when the campaign does not exist, 409 when the code belongs to another \
                    campaign, and 422 when the campaign is no longer editable, audienceType is changed, or \
                    the schedule and window fields are inconsistent.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Campaign updated",
                content = @Content(schema = @Schema(implementation = CampaignResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Campaign not found",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Campaign code already exists",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "422",
                description = "Campaign is no longer editable, or an immutable field was changed",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Forbidden - insufficient permissions",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/{campaignId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {MarketingPermissionRegistry.CAMPAIGN_EDIT})
    @PreAuthorize("hasAuthority('marketing:campaign:edit')")
    @EmitEvent(id = "MARKETING_CAMPAIGN_UPDATE", apiVersion = "1")
    public ResponseEntity<CampaignResponse> update(
            @PathVariable UUID campaignId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Full replacement campaign definition; audienceType must match the stored"
                                    + " value, and omitted optional fields are cleared.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Scheduled commercial campaign",
                                                            value = CAMPAIGN_EXAMPLE)))
                    @Valid
                    @RequestBody
                    UpsertCampaignRequest request) {
        return ResponseEntity.ok(campaignService.update(campaignId, request));
    }

    @Operation(operationId = "previewCampaignAudience", summary = "Preview Campaign Audience", description = """
                    Reports the campaign's last resolved audience snapshot: total segment members, whether that \
                    snapshot was truncated by the CRM's candidate ceiling, per-channel eligible counts after \
                    consent, staleness and suppression checks, a bounded per-channel sample of the individual \
                    decisions behind those counts, whether each channel has a template, and the readiness \
                    problems that would block scheduling.
                    Use this tool to gauge reach before scheduling or dispatch; do not use dispatchCampaign, \
                    which actually queues messages for delivery.
                    Preconditions: the campaign must exist and have a segmentId bound; membership is \
                    replicated asynchronously from pos-customer, so the counts reflect the snapshot taken at \
                    resolvedAt rather than live CRM data.
                    Required inputs: campaignId (UUID) as a path parameter; there is no request body.
                    Emits a MARKETING_AUDIENCE_PREVIEW audit event and, while the campaign is still DRAFT, \
                    SCHEDULED or PAUSED, asynchronously requests a fresher membership snapshot from \
                    pos-customer; the campaign itself is not modified.
                    Each sample entry carries the party id, the contact resolved to receive the message (the \
                    CRM's designated contact for a commercial account, the person themselves for an \
                    individual), and the machine-readable decision code; it never carries names, addresses or \
                    contact details, which this module does not hold.
                    Problems with the campaign's promotionOfferId or catalogFocusRef are reported in warnings \
                    rather than as errors, because a preview is where a marketer goes to find out what is \
                    wrong.
                    Returns 404 when the campaign does not exist, and 422 when no segment is bound so there \
                    is nothing to preview.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Preview returned",
                content = @Content(schema = @Schema(implementation = AudiencePreviewResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Campaign not found",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "422",
                description = "Campaign has no segment bound",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "503", description = "pos-customer unavailable", content = @Content),
        @ApiResponse(
                responseCode = "403",
                description = "Forbidden - insufficient permissions",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
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

    @Operation(operationId = "scheduleCampaign", summary = "Schedule Campaign For Dispatch", description = """
                    Validates a campaign's readiness and moves it to SCHEDULED, the state from which dispatch \
                    is allowed.
                    Use this tool when a DRAFT campaign is complete; do not use dispatchCampaign, which queues \
                    the actual sends and only accepts a campaign that is already SCHEDULED or SENDING.
                    Preconditions: the campaign must exist and be in DRAFT or PAUSED; it must have at least \
                    one channel, a bound segment that is known to this module, active, and matching the \
                    campaign's audienceType, and an existing template attached for every selected channel; \
                    any promotionOfferId must resolve in pos-price to an ACTIVE offer whose date window \
                    includes today, and any catalogFocusRef must be written as kind:value using one of \
                    product, sku, service or category.
                    Required inputs: campaignId (UUID) as a path parameter; there is no request body.
                    Emits a MARKETING_CAMPAIGN_SCHEDULE event and publishes a campaign-scheduled fact through \
                    the transactional outbox.
                    Returns 404 when the campaign does not exist, and 422 listing every readiness problem at \
                    once when the campaign is not ready or the transition is not legal from its current \
                    status.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Campaign scheduled",
                content = @Content(schema = @Schema(implementation = CampaignResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Campaign not found",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "422",
                description = "Campaign is not ready to schedule",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Forbidden - insufficient permissions",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
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

    @Operation(operationId = "pauseCampaign", summary = "Pause Campaign Dispatch", description = """
                    Moves a campaign to PAUSED so the send worker stops draining its queued recipients.
                    Use this tool to halt an in-flight or scheduled campaign without losing it; do not use \
                    cancelCampaign, which is terminal and cannot be undone.
                    Preconditions: the campaign must exist and be in SCHEDULED or SENDING, the only statuses \
                    that permit the PAUSED transition.
                    Required inputs: campaignId (UUID) as a path parameter; there is no request body.
                    Emits a MARKETING_CAMPAIGN_PAUSE event; already-delivered messages are unaffected and \
                    queued send rows remain PENDING for a later resume.
                    Returns 404 when the campaign does not exist, and 422 when its current status does not \
                    allow pausing.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Campaign paused",
                content = @Content(schema = @Schema(implementation = CampaignResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Campaign not found",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "422",
                description = "Illegal lifecycle transition",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Forbidden - insufficient permissions",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
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

    @Operation(operationId = "resumeCampaign", summary = "Resume Paused Campaign", description = """
                    Returns a PAUSED campaign to SCHEDULED so it re-enters the dispatch queue; the send worker \
                    decides when delivery actually restarts rather than jumping straight back into SENDING.
                    Use this tool to continue a campaign that pauseCampaign halted; do not use \
                    scheduleCampaign, which re-runs readiness validation on a DRAFT or PAUSED campaign.
                    Preconditions: the campaign must exist and be PAUSED.
                    Required inputs: campaignId (UUID) as a path parameter; there is no request body.
                    Emits a MARKETING_CAMPAIGN_RESUME event; recipients already recorded as sent are never \
                    re-queued, because each recipient-channel pair is unique per campaign.
                    Returns 404 when the campaign does not exist, and 422 when its current status does not \
                    allow resuming.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Campaign resumed",
                content = @Content(schema = @Schema(implementation = CampaignResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Campaign not found",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "422",
                description = "Illegal lifecycle transition",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Forbidden - insufficient permissions",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
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

    @Operation(operationId = "cancelCampaign", summary = "Cancel Campaign Permanently", description = """
                    Moves a campaign to CANCELLED, a terminal state from which no further transition exists.
                    Use this tool to abandon a campaign for good; do not use pauseCampaign, which halts \
                    dispatch reversibly and keeps the queue intact.
                    Preconditions: the campaign must exist and be in DRAFT, SCHEDULED, SENDING or PAUSED; a \
                    SENT campaign can only be CLOSED, and CANCELLED and CLOSED campaigns cannot transition at \
                    all.
                    Required inputs: campaignId (UUID) as a path parameter; there is no request body.
                    Emits a MARKETING_CAMPAIGN_CANCEL event; messages already delivered are not recalled.
                    Returns 404 when the campaign does not exist, and 422 when its current status does not \
                    allow cancellation.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Campaign cancelled",
                content = @Content(schema = @Schema(implementation = CampaignResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Campaign not found",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "422",
                description = "Illegal lifecycle transition",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Forbidden - insufficient permissions",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
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

    @Operation(operationId = "dispatchCampaign", summary = "Dispatch Campaign To Audience", description = """
                    Materializes the campaign's resolved audience into one PENDING send row per recipient and \
                    channel, moves a SCHEDULED campaign to SENDING, and returns 202 with the queued count \
                    while the send worker delivers asynchronously.
                    Use this tool to start or retry delivery; do not use scheduleCampaign, which only \
                    validates readiness, and do not use previewCampaignAudience, which reports reach without \
                    queueing anything.
                    Preconditions: the campaign must exist, be SCHEDULED or SENDING, and have a segment \
                    bound; the audience is the last snapshot replicated from pos-customer.
                    Required inputs: campaignId (UUID) as a path parameter; there is no request body.
                    Emits a MARKETING_CAMPAIGN_SEND event; the call is idempotent because each \
                    recipient-channel pair is unique per campaign, so re-invoking never double-sends, and \
                    when no audience snapshot has arrived yet it queues nothing, asynchronously requests \
                    resolution from pos-customer, and reports queued 0 for a later retry.
                    Returns 404 when the campaign does not exist, and 422 when it is not SCHEDULED or \
                    SENDING or has no segment bound.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Dispatch accepted"),
        @ApiResponse(
                responseCode = "404",
                description = "Campaign not found",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "422",
                description = "Campaign is not in a dispatchable state",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "503", description = "pos-customer unavailable", content = @Content),
        @ApiResponse(
                responseCode = "403",
                description = "Forbidden - insufficient permissions",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
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

    @Operation(operationId = "listCampaignSends", summary = "List Campaign Send Records", description = """
                    Lists the per-recipient, per-channel send rows a campaign dispatch queued, including \
                    status, failure reason, provider message id, attempts and timestamps.
                    Use this tool to inspect individual delivery outcomes; use getCampaignStats instead for \
                    aggregate funnel and attribution numbers.
                    Preconditions: none; an unknown campaignId yields an empty page rather than an error.
                    Required inputs: campaignId (UUID) as a path parameter; page defaults to 0 and size \
                    defaults to 50, clamped between 1 and 200, ordered by queuedAt ascending.
                    Emits a MARKETING_CAMPAIGN_SENDS_LIST audit event; no marketing state is changed and \
                    this is a read-only projection.
                    Returns 200 with an empty page when the campaign has no send rows, so an empty result is \
                    not an error condition.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Send records returned",
                content = @Content(schema = @Schema(implementation = PagedResponse.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Forbidden - insufficient permissions",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
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
