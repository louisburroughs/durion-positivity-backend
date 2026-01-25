package com.positivity.customer.controller;

import com.positivity.customer.security.CrmPermissionRegistry;
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
    @GetMapping("/{partyId}/contacts")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_VIEW + "')")
    public ResponseEntity<Void> getContactsWithRoles(
            @PathVariable String partyId) {
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
    @PutMapping("/{partyId}/contacts/{contactId}/roles")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_ROLE_ASSIGN + "')")
    public ResponseEntity<Void> updateContactRoles(
            @PathVariable String partyId,
            @PathVariable String contactId,
            @RequestBody(required = false) Object body) {
        log.info("Stub updateContactRoles partyId={} contactId={}", partyId, contactId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
