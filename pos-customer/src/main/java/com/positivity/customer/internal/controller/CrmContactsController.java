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
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
    @Operation(operationId = "getContactsWithRoles", summary = "Get Party Contacts With Roles", description = """
                    Returns every contact person assigned a role on a commercial account, with names, \
                    emails, and phones resolved from pos-people and each contact's role assignments and \
                    primary flags.
                    Use this tool when listing who represents a commercial account and in what capacity; do \
                    not use updateContactRoles, which rewrites one contact's role assignments.
                    Preconditions: a commercial party must exist for the supplied partyId; a pos-people \
                    outage degrades contact names and contact points to null rather than failing.
                    Required inputs: partyId (UUID) as a path parameter; there is no request body.
                    Emits a CRM_CONTACTS_LIST audit event; no state changes occur.
                    Returns 404 when no commercial party exists for the supplied partyId, and 200 with an \
                    empty contacts list when the account has no role assignments.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Contacts retrieved successfully",
                        content = @Content(schema = @Schema(implementation = GetContactsWithRolesResponse.class))),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content),
                @ApiResponse(responseCode = "404", description = "Party not found", content = @Content)
            })
    @GetMapping("/{partyId}/contacts")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.CONTACT_VIEW})
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
    @Operation(operationId = "updateContactRoles", summary = "Update Contact Role Assignments", description = """
                    Replaces the full set of role assignments for one contact on a commercial account; \
                    existing assignments for that contact are deleted and the submitted list is written in \
                    their place.
                    Use this tool when changing what a known contact does for an account; use \
                    getContactsWithRoles instead to read current assignments, and note that submitting an \
                    empty roles list removes the contact's roles entirely.
                    Preconditions: the commercial party and the contact person must both exist; assigning a \
                    role as primary automatically demotes any existing primary contact for that role.
                    Required inputs: partyId and contactId (UUIDs) as path parameters, plus roles, a list \
                    where each entry has roleCode (BILLING, PAYMENT_AUTHORIZER, OPERATIONS, \
                    PRIMARY_BUSINESS_CONTACT, or TECHNICAL) and an optional isPrimary flag defaulting to \
                    false.
                    Emits a CRM_CONTACT_ROLES_UPDATE event; assignments are rewritten in place.
                    Returns 404 when the party or contact person cannot be found, and 400 when a roleCode is \
                    not a recognized role.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Roles updated successfully",
                        content = @Content(schema = @Schema(implementation = UpdateContactRolesResponse.class))),
                @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content),
                @ApiResponse(responseCode = "404", description = "Party or contact not found", content = @Content)
            })
    @PutMapping("/{partyId}/contacts/{contactId}/roles")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.CONTACT_ROLE_ASSIGN})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_ROLE_ASSIGN + "')")
    @EmitEvent(id = "CRM_CONTACT_ROLES_UPDATE", apiVersion = "1")
    public ResponseEntity<UpdateContactRolesResponse> updateContactRoles(
            @Parameter(description = "Party ID", required = true) @PathVariable @NonNull UUID partyId,
            @Parameter(description = "Contact ID", required = true) @PathVariable @NonNull UUID contactId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "The complete replacement set of role assignments for this contact on the account.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Primary billing contact", value = """
                                                                    {"roles":[{"roleCode":"BILLING","isPrimary":true},
                                                                              {"roleCode":"OPERATIONS","isPrimary":false}]}
                                                                    """)))
                    @RequestBody
                    @NonNull
                    UpdateContactRolesRequest request) {

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
