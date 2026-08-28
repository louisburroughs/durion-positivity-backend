package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.config.SuppressionService;
import com.positivity.customer.internal.dto.AddSuppressionRequest;
import com.positivity.customer.internal.dto.PagedResponse;
import com.positivity.customer.internal.dto.SuppressionEntryResponse;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hard address-level marketing suppression (Story #1140).
 *
 * <p>Entries here outrank consent and are consulted by every send. Raw addresses are never
 * stored or returned — only a normalized hash for lookup and a masked hint for review.
 */
@Tag(name = "CRM Suppression", description = "Hard address-level marketing suppression list")
@RestController
@RequestMapping("/v1/crm/suppression")
public class CrmSuppressionController {

    private final SuppressionService suppressionService;

    public CrmSuppressionController(SuppressionService suppressionService) {
        this.suppressionService = suppressionService;
    }

    @Operation(operationId = "listSuppressionEntries", summary = "List Suppression Entries", description = """
                    Returns hard-suppressed marketing addresses newest first, showing only a masked address \
                    hint and the normalized hash — raw addresses are never stored or returned.
                    Use this tool when reviewing the suppression list; use checkAddressSuppression instead \
                    to test one specific address before a send.
                    Preconditions: none; an empty page is returned when nothing matches.
                    Required inputs: none; channel optionally filters on EMAIL or SMS, page defaults to 0, \
                    and size defaults to 50 clamped between 1 and 200.
                    Emits a CRM_SUPPRESSION_LIST audit event; no state changes occur.
                    Returns 200 with an empty page rather than an error when the list is empty.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Entries returned",
                content = @Content(schema = @Schema(implementation = PagedResponse.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.SUPPRESSION_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.SUPPRESSION_VIEW + "')")
    @EmitEvent(id = "CRM_SUPPRESSION_LIST", apiVersion = "1")
    public ResponseEntity<PagedResponse<SuppressionEntryResponse>> list(
            @RequestParam(name = "channel", required = false) MarketingChannel channel,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(suppressionService.list(channel, page, size));
    }

    @Operation(operationId = "checkAddressSuppression", summary = "Check Address Suppression", description = """
                    Reports whether a raw address is currently hard-blocked on a channel by normalizing and \
                    hashing it and looking the hash up in the suppression list.
                    Use this tool from send pipelines immediately before dispatch; use \
                    resolveMarketingEligibility instead for the full party-level decision that also folds \
                    in consent and the account gate.
                    Preconditions: none; suppression outranks consent, so a true result must block the send \
                    regardless of opt-in state.
                    Required inputs: channel (EMAIL or SMS) and address (the raw address to test) as \
                    required query parameters; the raw address is used only for hashing and never stored.
                    Emits a CRM_SUPPRESSION_CHECK audit event; no state changes occur.
                    Returns 200 with a bare boolean body, true when the address is suppressed on that \
                    channel and false otherwise.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Result returned"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping("/check")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.SUPPRESSION_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.SUPPRESSION_VIEW + "')")
    @EmitEvent(id = "CRM_SUPPRESSION_CHECK", apiVersion = "1")
    public ResponseEntity<Boolean> check(
            @RequestParam(name = "channel") MarketingChannel channel, @RequestParam(name = "address") String address) {
        return ResponseEntity.ok(suppressionService.isSuppressed(channel, address));
    }

    @Operation(operationId = "addSuppressionEntry", summary = "Suppress Marketing Address", description = """
                    Hard-blocks an address from marketing on one channel, storing only a normalized hash \
                    and a masked hint of the address.
                    Use this tool for bounce feeds, spam complaints, and legal do-not-contact requests; do \
                    not use updateMarketingConsent, which records a party's choice and can be reversed by \
                    the party, whereas suppression outranks consent entirely.
                    Preconditions: none; the call is idempotent, and re-adding an already-suppressed \
                    address returns the existing entry without touching the audit trail.
                    Required inputs: channel (EMAIL or SMS), address (raw, max 320 characters), and reason \
                    (HARD_BOUNCE, SPAM_COMPLAINT, LEGAL_DNC, MANUAL, or UNSUBSCRIBE_LINK); partyId is \
                    optional and source defaults to CSR.
                    Emits a CRM_SUPPRESSION_ADD event and publishes a suppression-changed fact when a new \
                    entry is actually created.
                    Returns 400 when channel, address, or reason is missing, and 201 with the existing \
                    entry when the address was already suppressed.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Address suppressed",
                content = @Content(schema = @Schema(implementation = SuppressionEntryResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.SUPPRESSION_MANAGE})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.SUPPRESSION_MANAGE + "')")
    @EmitEvent(id = "CRM_SUPPRESSION_ADD", apiVersion = "1")
    public ResponseEntity<SuppressionEntryResponse> add(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "The address to hard-block, the channel it applies to, and why it is being suppressed.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Hard bounce", value = """
                                                                    {"channel":"EMAIL",
                                                                     "address":"bounced@example.com",
                                                                     "partyId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "reason":"HARD_BOUNCE",
                                                                     "source":"SYSTEM"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    AddSuppressionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(suppressionService.add(request));
    }

    @Operation(operationId = "removeSuppressionEntry", summary = "Lift Address Suppression", description = """
                    Removes one suppression entry, allowing marketing to reach the address again subject to \
                    normal consent rules.
                    Use this tool when an address was suppressed in error or a legal block is lifted; do \
                    not use updateMarketingConsent for this, which cannot override a suppression entry.
                    Preconditions: the suppression entry must exist.
                    Required inputs: suppressionId (UUID) as a path parameter; there is no request body.
                    Emits a CRM_SUPPRESSION_REMOVE event and publishes a suppression-changed fact so \
                    consumers stop blocking the address.
                    Returns 404 when no suppression entry exists for the supplied suppressionId.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Suppression lifted", content = @Content),
        @ApiResponse(responseCode = "404", description = "Suppression entry not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @DeleteMapping("/{suppressionId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.SUPPRESSION_MANAGE})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.SUPPRESSION_MANAGE + "')")
    @EmitEvent(id = "CRM_SUPPRESSION_REMOVE", apiVersion = "1")
    public ResponseEntity<Void> remove(@PathVariable UUID suppressionId) {
        suppressionService.remove(suppressionId);
        return ResponseEntity.noContent().build();
    }
}
