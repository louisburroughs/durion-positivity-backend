package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.ConsentEventResponse;
import com.positivity.customer.internal.dto.MarketingConsentDecision;
import com.positivity.customer.internal.dto.MarketingConsentSummaryResponse;
import com.positivity.customer.internal.dto.PagedResponse;
import com.positivity.customer.internal.dto.UpdateMarketingConsentRequest;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.service.MarketingConsentService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Marketing consent, the commercial account master gate, and the consent audit trail
 * (Stories #1138, #1139).
 */
@Tag(name = "CRM Marketing Consent", description = "Per-channel marketing consent, account gate, and consent audit")
@RestController
@RequestMapping("/v1/crm/parties/{partyId}")
public class CrmConsentController {

    private final MarketingConsentService marketingConsentService;

    public CrmConsentController(MarketingConsentService marketingConsentService) {
        this.marketingConsentService = marketingConsentService;
    }

    @Operation(operationId = "getMarketingConsent", summary = "Get Marketing Consent Summary", description = """
                    Returns a party's per-channel marketing consent (EMAIL and SMS, each OPT_IN, OPT_OUT, or \
                    UNSET) together with the account gate, quiet hours, monthly send cap, and the resolved \
                    send eligibility per channel.
                    Use this tool when reviewing a party's full consent state; use resolveMarketingEligibility \
                    instead when only a single allow or deny decision for one channel is needed.
                    Preconditions: the party must exist as either a commercial or person party.
                    Required inputs: partyId (UUID) as a path parameter; there is no request body.
                    Emits a CRM_MARKETING_CONSENT_GET audit event; no state changes occur.
                    Returns 404 when no party exists for the supplied partyId.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Consent returned",
                content = @Content(schema = @Schema(implementation = MarketingConsentSummaryResponse.class))),
        @ApiResponse(responseCode = "404", description = "Party not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping("/marketing-consent")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.CONSENT_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONSENT_VIEW + "')")
    @EmitEvent(id = "CRM_MARKETING_CONSENT_GET", apiVersion = "1")
    public ResponseEntity<MarketingConsentSummaryResponse> getConsent(@PathVariable UUID partyId) {
        return ResponseEntity.ok(marketingConsentService.getConsent(partyId));
    }

    @Operation(operationId = "updateMarketingConsent", summary = "Update Marketing Consent", description = """
                    Updates a party's per-channel marketing consent; only channels present in the request \
                    change, and each actual change appends one immutable consent-audit entry attributed to \
                    the calling user.
                    Use this tool when a party grants or withdraws marketing consent; do not use \
                    setAccountMarketingGate, which is the account-level hard gate overriding all contact \
                    consent, and do not use upsertCommunicationPreferences, which stores transactional \
                    channel preferences rather than marketing consent.
                    Preconditions: the party must exist as either a commercial or person party; re-submitting \
                    an unchanged value is an idempotent no-op that writes no audit row.
                    Required inputs: partyId (UUID) as a path parameter; marketingEmailConsent and \
                    marketingSmsConsent take OPT_IN, OPT_OUT, or UNSET, source defaults to CSR, and \
                    optOutReason, quietHoursStart, quietHoursEnd, and maxMarketingSendsPerMonth are optional.
                    Emits a CRM_MARKETING_CONSENT_UPDATE event and republishes the resolved eligibility \
                    decision for both channels so downstream consumers never hold a stale allow.
                    Returns 404 when no party exists for the supplied partyId.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Consent updated",
                content = @Content(schema = @Schema(implementation = MarketingConsentSummaryResponse.class))),
        @ApiResponse(responseCode = "404", description = "Party not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PutMapping("/marketing-consent")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.CONSENT_MANAGE})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONSENT_MANAGE + "')")
    @EmitEvent(id = "CRM_MARKETING_CONSENT_UPDATE", apiVersion = "1")
    public ResponseEntity<MarketingConsentSummaryResponse> updateConsent(
            @PathVariable UUID partyId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Partial consent update; only the channels and settings present in the body are changed.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Email opt-out via CSR", value = """
                                                                    {"marketingEmailConsent":"OPT_OUT",
                                                                     "optOutReason":"NOT_INTERESTED",
                                                                     "source":"CSR"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    UpdateMarketingConsentRequest request) {
        return ResponseEntity.ok(marketingConsentService.updateConsent(partyId, request));
    }

    @Operation(operationId = "setAccountMarketingGate", summary = "Set Account Marketing Gate", description = """
                    Sets or clears the commercial-account marketing hard gate; while set, no marketing \
                    reaches the account on any channel regardless of individual contact consent.
                    Use this tool when an entire commercial account must be excluded from marketing; do not \
                    use updateMarketingConsent, which changes one party's per-channel consent and cannot \
                    override the gate.
                    Preconditions: the party must exist as a commercial party; person parties have no \
                    account gate, and setting the gate to its current value is an idempotent no-op.
                    Required inputs: partyId (UUID) as a path parameter and optOut (boolean) as a query \
                    parameter, where true engages the gate and false clears it; there is no request body.
                    Emits a CRM_MARKETING_ACCOUNT_GATE_SET event, and when the gate actually flips it \
                    republishes the resolved eligibility decisions for both channels.
                    Returns 404 when no commercial party exists for the supplied partyId.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Gate updated",
                content = @Content(schema = @Schema(implementation = MarketingConsentSummaryResponse.class))),
        @ApiResponse(responseCode = "404", description = "Commercial party not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PutMapping("/marketing-consent/account-gate")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.CONSENT_MANAGE})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONSENT_MANAGE + "')")
    @EmitEvent(id = "CRM_MARKETING_ACCOUNT_GATE_SET", apiVersion = "1")
    public ResponseEntity<MarketingConsentSummaryResponse> setAccountGate(
            @PathVariable UUID partyId, @RequestParam(name = "optOut") boolean optOut) {
        return ResponseEntity.ok(marketingConsentService.setAccountMarketingOptOut(partyId, optOut));
    }

    @Operation(operationId = "getConsentHistory", summary = "Get Consent Audit History", description = """
                    Returns the append-only marketing-consent audit trail for a party, newest first, with \
                    one entry per actual consent change recording the channel, old and new values, reason, \
                    source, and acting user.
                    Use this tool when auditing how a party's consent reached its current state; use \
                    getMarketingConsent instead for the current consent summary and eligibility.
                    Preconditions: none; a party with no recorded consent changes yields an empty page \
                    rather than an error.
                    Required inputs: partyId (UUID) as a path parameter; page defaults to 0 and size \
                    defaults to 50 with a clamp between 1 and 200.
                    No events are emitted by the consent trail itself beyond the CRM_CONSENT_HISTORY_GET \
                    audit event this read emits; no state changes occur.
                    Returns 200 with an empty page rather than an error when no history exists.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "History returned",
                content = @Content(schema = @Schema(implementation = PagedResponse.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping("/consent-history")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.CONSENT_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONSENT_VIEW + "')")
    @EmitEvent(id = "CRM_CONSENT_HISTORY_GET", apiVersion = "1")
    public ResponseEntity<PagedResponse<ConsentEventResponse>> getConsentHistory(
            @PathVariable UUID partyId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(marketingConsentService.getConsentHistory(partyId, page, size));
    }

    @Operation(
            operationId = "resolveMarketingEligibility",
            summary = "Resolve Marketing Send Eligibility",
            description = """
                    Resolves a single allow-or-deny marketing send decision for one party and channel by \
                    folding together the account gate, the governing party's consent, and the suppression \
                    list, where suppression is a hard block layered on top of an opt-in.
                    Use this tool immediately before sending marketing to a party; use getMarketingConsent \
                    instead when the full consent summary rather than one decision is needed.
                    Preconditions: none; unknown parties resolve to a deny decision rather than an error.
                    Required inputs: partyId (UUID) as a path parameter and channel (EMAIL or SMS) as a \
                    required query parameter.
                    Emits a CRM_MARKETING_ELIGIBILITY_GET audit event; no state changes occur.
                    Returns 200 with allowed false and a reason code such as SUPPRESSED when the send must \
                    not happen, and 400 when channel is not EMAIL or SMS.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Decision returned",
                content = @Content(schema = @Schema(implementation = MarketingConsentDecision.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping("/marketing-eligibility")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.CONSENT_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONSENT_VIEW + "')")
    @EmitEvent(id = "CRM_MARKETING_ELIGIBILITY_GET", apiVersion = "1")
    public ResponseEntity<MarketingConsentDecision> resolveEligibility(
            @PathVariable UUID partyId, @RequestParam(name = "channel") MarketingChannel channel) {
        return ResponseEntity.ok(marketingConsentService.resolveEligibility(partyId, channel));
    }
}
