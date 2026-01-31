package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CRM Contacts Controller
 * 
 * Handles contact point (email, phone) and contact role management for parties.
 * Endpoints for retrieving contacts with roles and managing role assignments.
 * 
 * Issue #172: Contacts: Maintain Contact Roles and Primary Flags
 */
@Tag(name = "CRM Contacts", description = "Contact point and role management for parties (stub endpoints)")
@RestController
@RequestMapping("/v1/crm/parties")
public class CrmContactsController {

    private static final Logger log = LoggerFactory.getLogger(CrmContactsController.class);

    /**
     * Get all contacts for a party with their roles.
     * 
     * GET /v1/crm/parties/{partyId}/contacts
     * Requires: CONTACT_VIEW permission
     */
    @Operation(summary = "Get contacts with roles", description = "Retrieve all contact points for a party with their role assignments (stub endpoint)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "501", description = "Not implemented", content = @Content),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping("/{partyId}/contacts")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_VIEW + "')")
    public ResponseEntity<Void> getContactsWithRoles(
            @Parameter(description = "Party ID", required = true) @PathVariable String partyId) {
        log.info("Stub getContactsWithRoles partyId={}", partyId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    /**
     * Update contact roles for a specific contact within a party.
     * 
     * PUT /v1/crm/parties/{partyId}/contacts/{contactId}/roles
     * Requires: CONTACT_ROLE_ASSIGN permission
     * 
     * Note: Current implementation returns success but does not persist roles.
     * Role persistence will be implemented when ContactRole entity is added.
     */
    @Operation(summary = "Update contact roles", description = "Assign or update role assignments for a specific contact within a party (stub endpoint)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "501", description = "Not implemented", content = @Content),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PutMapping("/{partyId}/contacts/{contactId}/roles")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_ROLE_ASSIGN + "')")
    @EmitEvent(id = "CUSTOMER_CONTACT_ROLE_UPDATE_LEGACY", apiVersion = "1")
    public ResponseEntity<Void> updateContactRoles(
            @Parameter(description = "Party ID", required = true) @PathVariable String partyId,
            @Parameter(description = "Contact ID", required = true) @PathVariable String contactId,
            @Parameter(description = "Role assignment request", required = false) @RequestBody(required = false) Object body) {
        log.info("Stub updateContactRoles partyId={} contactId={}", partyId, contactId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
