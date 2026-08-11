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

    @Operation(
            summary = "List party interactions",
            description = "Interaction history for a party, newest first, optionally filtered by type")
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
    @PreAuthorize("hasAuthority('crm:interaction:view')")
    @EmitEvent(id = "CRM_INTERACTION_LIST", apiVersion = "1")
    public ResponseEntity<PagedResponse<CustomerInteractionResponse>> list(
            @PathVariable UUID partyId,
            @RequestParam(name = "type", required = false) InteractionType type,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(interactionService.listForParty(partyId, type, page, size));
    }

    @Operation(
            summary = "Record an interaction",
            description = "Record a CSR-initiated touch (call, email, note) on a party's timeline")
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
    @PreAuthorize("hasAuthority('crm:interaction:manage')")
    @EmitEvent(id = "CRM_INTERACTION_RECORD", apiVersion = "1")
    public ResponseEntity<CustomerInteractionResponse> record(
            @PathVariable UUID partyId, @Valid @RequestBody RecordInteractionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(interactionService.record(partyId, request));
    }
}
