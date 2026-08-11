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

    @Operation(
            summary = "Get marketing consent",
            description = "Per-channel marketing consent plus the effective send eligibility for each channel")
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
    @PreAuthorize("hasAuthority('crm:consent:view')")
    @EmitEvent(id = "CRM_MARKETING_CONSENT_GET", apiVersion = "1")
    public ResponseEntity<MarketingConsentSummaryResponse> getConsent(@PathVariable UUID partyId) {
        return ResponseEntity.ok(marketingConsentService.getConsent(partyId));
    }

    @Operation(
            summary = "Update marketing consent",
            description =
                    "Update per-channel marketing consent. Only channels present in the request change; each change writes one immutable consent-audit entry.")
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
    @PreAuthorize("hasAuthority('crm:consent:manage')")
    @EmitEvent(id = "CRM_MARKETING_CONSENT_UPDATE", apiVersion = "1")
    public ResponseEntity<MarketingConsentSummaryResponse> updateConsent(
            @PathVariable UUID partyId, @Valid @RequestBody UpdateMarketingConsentRequest request) {
        return ResponseEntity.ok(marketingConsentService.updateConsent(partyId, request));
    }

    @Operation(
            summary = "Set account marketing gate",
            description =
                    "Set or clear the commercial-account hard gate. When set, no marketing reaches the account on any channel regardless of individual contact consent.")
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
    @PreAuthorize("hasAuthority('crm:consent:manage')")
    @EmitEvent(id = "CRM_MARKETING_ACCOUNT_GATE_SET", apiVersion = "1")
    public ResponseEntity<MarketingConsentSummaryResponse> setAccountGate(
            @PathVariable UUID partyId, @RequestParam(name = "optOut") boolean optOut) {
        return ResponseEntity.ok(marketingConsentService.setAccountMarketingOptOut(partyId, optOut));
    }

    @Operation(
            summary = "Get consent history",
            description = "Append-only marketing-consent audit trail for a party, newest first")
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
    @PreAuthorize("hasAuthority('crm:consent:view')")
    @EmitEvent(id = "CRM_CONSENT_HISTORY_GET", apiVersion = "1")
    public ResponseEntity<PagedResponse<ConsentEventResponse>> getConsentHistory(
            @PathVariable UUID partyId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(marketingConsentService.getConsentHistory(partyId, page, size));
    }

    @Operation(
            summary = "Resolve send eligibility",
            description =
                    "Fold the account gate, governing contact consent, and suppression into a single allow/deny decision for one channel")
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
    @PreAuthorize("hasAuthority('crm:consent:view')")
    @EmitEvent(id = "CRM_MARKETING_ELIGIBILITY_GET", apiVersion = "1")
    public ResponseEntity<MarketingConsentDecision> resolveEligibility(
            @PathVariable UUID partyId, @RequestParam(name = "channel") MarketingChannel channel) {
        return ResponseEntity.ok(marketingConsentService.resolveEligibility(partyId, channel));
    }
}
