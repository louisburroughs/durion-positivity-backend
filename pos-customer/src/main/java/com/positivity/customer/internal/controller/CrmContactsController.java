package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.GetContactsWithRolesResponse;
import com.positivity.customer.internal.dto.UpdateContactRolesRequest;
import com.positivity.customer.internal.dto.UpdateContactRolesResponse;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.service.ContactRoleService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CRM Contacts Controller
 *
 * Handles contact point (email, phone) and contact role management for parties.
 * Endpoints for retrieving contacts with roles and managing role assignments.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/108">Backend
 *      Issue #108</a>
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/106">Backend
 *      Issue #106</a>
 */
@Tag(name = "CRM Contacts", description = "Contact point and role management for parties")
@RestController
@RequestMapping("/v1/crm/parties")
public class CrmContactsController {

    private static final Logger log = LoggerFactory.getLogger(CrmContactsController.class);

    private final ContactRoleService contactRoleService;

    public CrmContactsController(ContactRoleService contactRoleService) {
        this.contactRoleService = contactRoleService;
    }

    /**
     * Get all contacts for a party with their roles.
     *
     * GET /v1/crm/parties/{partyId}/contacts
     * Requires: CONTACT_VIEW permission
     */
    @Operation(summary = "Get contacts with roles", description = "Retrieve all contacts for a party including their role assignments")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Contacts retrieved successfully", content = @Content(schema = @Schema(implementation = GetContactsWithRolesResponse.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content),
            @ApiResponse(responseCode = "404", description = "Party not found", content = @Content)
    })
    @GetMapping("/{partyId}/contacts")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
            CrmPermissionRegistry.CONTACT_VIEW })
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_VIEW + "')")
    @EmitEvent(id = "CRM_CONTACTS_LIST", apiVersion = "1")
    public ResponseEntity<GetContactsWithRolesResponse> getContactsWithRoles(
            @Parameter(description = "Party ID", required = true) @PathVariable @NonNull UUID partyId) {

        try {
            GetContactsWithRolesResponse response = contactRoleService.getContactsWithRoles(partyId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to get contacts with roles: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Update contact roles for a specific contact within a party.
     *
     * PUT /v1/crm/parties/{partyId}/contacts/{contactId}/roles
     * Requires: CONTACT_ROLE_ASSIGN permission
     */
    @Operation(summary = "Update contact roles", description = "Assign or update role assignments for a specific contact within a party")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Roles updated successfully", content = @Content(schema = @Schema(implementation = UpdateContactRolesResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content),
            @ApiResponse(responseCode = "404", description = "Party or contact not found", content = @Content)
    })
    @PutMapping("/{partyId}/contacts/{contactId}/roles")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
            CrmPermissionRegistry.CONTACT_ROLE_ASSIGN })
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_ROLE_ASSIGN + "')")
    @EmitEvent(id = "CRM_CONTACT_ROLES_UPDATE", apiVersion = "1")
    public ResponseEntity<UpdateContactRolesResponse> updateContactRoles(
            @Parameter(description = "Party ID", required = true) @PathVariable @NonNull UUID partyId,
            @Parameter(description = "Contact ID", required = true) @PathVariable @NonNull UUID contactId,
            @Parameter(description = "Role assignment request", required = true) @RequestBody @NonNull UpdateContactRolesRequest request) {

        try {
            UpdateContactRolesResponse response = contactRoleService.updateContactRoles(partyId, contactId, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to update contact roles: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("Business rule violation: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }
}
