package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.CustomerInteractionResponse;
import com.positivity.customer.internal.dto.PagedResponse;
import com.positivity.customer.internal.dto.RecordInteractionRequest;
import com.positivity.customer.internal.enums.InteractionType;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.service.CustomerInteractionService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A party's interaction timeline (Story #1141): campaign sends, CSR touches, and workorder
 * notes in one history.
 */
@Tag(name = "CRM Interactions", description = "Party interaction and touch history")
@RestController
@RequestMapping("/v1/crm/parties/{partyId}/interactions")
public class CrmInteractionController {

    private final CustomerInteractionService interactionService;

    public CrmInteractionController(CustomerInteractionService interactionService) {
        this.interactionService = interactionService;
    }

    @Operation(operationId = "listPartyInteractions", summary = "List Party Interaction Timeline", description = """
                    Returns a party's interaction timeline newest first, unifying campaign sends, CSR \
                    touches, and workorder notes, with interaction bodies passed through redaction before \
                    they are returned.
                    Use this tool when reviewing a party's touch history; do not use recordInteraction, which \
                    appends a new entry to the timeline instead of reading it.
                    Preconditions: none; an unknown partyId simply yields an empty page rather than an error.
                    Required inputs: partyId (UUID) as a path parameter; type optionally filters to one of \
                    CAMPAIGN_SEND, EMAIL, SMS, CALL, FOLLOW_UP, NOTE, or WORKORDER_NOTE, page defaults to 0, \
                    and size defaults to 50 with a clamp between 1 and 200.
                    Emits a CRM_INTERACTION_LIST audit event; no state changes occur.
                    Returns 200 with an empty page rather than an error when the party has no interactions.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Interactions returned",
                content = @Content(schema = @Schema(implementation = PagedResponse.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.INTERACTION_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.INTERACTION_VIEW + "')")
    @EmitEvent(id = "CRM_INTERACTION_LIST", apiVersion = "1")
    public ResponseEntity<PagedResponse<CustomerInteractionResponse>> list(
            @PathVariable UUID partyId,
            @RequestParam(name = "type", required = false) InteractionType type,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(interactionService.listForParty(partyId, type, page, size));
    }

    @Operation(operationId = "recordInteraction", summary = "Record Party Interaction", description = """
                    Records a CSR-initiated touch such as a call, email, or note on a party's interaction \
                    timeline, stamping the acting username from the security context.
                    Use this tool when logging a manual customer touch; do not use listPartyInteractions, \
                    which reads the timeline, and note that campaign and workorder interactions are ingested \
                    from events rather than through this endpoint.
                    Preconditions: none are checked against the party; the interaction is stored against the \
                    supplied partyId as-is.
                    Required inputs: partyId (UUID) as a path parameter and type (CAMPAIGN_SEND, EMAIL, SMS, \
                    CALL, FOLLOW_UP, NOTE, or WORKORDER_NOTE) in the body; direction defaults to OUTBOUND, \
                    occurredAt defaults to now, and channel accepts EMAIL or SMS.
                    Emits a CRM_INTERACTION_RECORD event and persists the interaction row.
                    Returns 400 when type is missing or subject, summary, or body exceed their length limits.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Interaction recorded",
                content = @Content(schema = @Schema(implementation = CustomerInteractionResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.INTERACTION_MANAGE})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.INTERACTION_MANAGE + "')")
    @EmitEvent(id = "CRM_INTERACTION_RECORD", apiVersion = "1")
    public ResponseEntity<CustomerInteractionResponse> record(
            @PathVariable UUID partyId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "The CSR touch to append to the party's timeline, typed by interaction kind and optional channel.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Follow-up call note", value = """
                                                                    {"type":"CALL",
                                                                     "direction":"OUTBOUND",
                                                                     "subject":"Brake job follow-up",
                                                                     "summary":"Customer satisfied, will book alignment next month",
                                                                     "occurredAt":"2026-08-10T15:30:00Z"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    RecordInteractionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(interactionService.record(partyId, request));
    }
}
