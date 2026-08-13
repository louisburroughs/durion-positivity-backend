package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.CreatePartyRelationshipRequest;
import com.positivity.customer.internal.dto.CreatePartyRelationshipResponse;
import com.positivity.customer.internal.dto.GetCommercialAccountContactsResponse;
import com.positivity.customer.internal.enums.PartyRelationshipRole;
import com.positivity.customer.service.PartyRelationshipService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for managing Party-Person relationships in the CRM domain.
 * <p>
 * Implements the following endpoints per Issue #110 (Associate Individuals to
 * Commercial Account):
 * - POST /v1/crm/commercial-accounts/{partyId}/relationships - Create a
 * relationship
 * - GET /v1/crm/commercial-accounts/{partyId}/contacts - Get contacts for an
 * account
 * - PUT
 * /v1/crm/commercial-accounts/{partyId}/relationships/{relationshipId}/primary-billing
 * - Designate primary billing
 * - DELETE /v1/crm/commercial-accounts/{partyId}/relationships/{relationshipId}
 * - Deactivate relationship
 * </p>
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/110">Backend
 *      Issue #110</a>
 */
@Slf4j
@RestController
@RequestMapping("/v1/crm/commercial-accounts/{partyId}")
@RequiredArgsConstructor
@Tag(name = "CRM Party Relationships", description = "Commercial account relationship management APIs")
public class CrmPartyRelationshipController {

    private static final String ANONYMOUS = "anonymous";
    private final PartyRelationshipService partyRelationshipService;

    /**
     * Creates a new relationship between a commercial account and an individual.
     * <p>
     * Business Rules:
     * - At least one role is required
     * - If isPrimaryBillingContact is true, must have BILLING role
     * - Setting as primary billing atomically demotes any existing primary
     * </p>
     *
     * @param partyId   the commercial account party ID
     * @param request   the relationship creation request
     * @param principal the authenticated user
     * @return the created relationship with assigned ID
     */
    @PostMapping("/relationships")
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"crm:relationship:create"})
    @PreAuthorize("hasAuthority('crm:relationship:create')")
    @EmitEvent(id = "CRM_RELATIONSHIP_CREATE", apiVersion = "1")
    @Operation(operationId = "createPartyRelationship", summary = "Create Party Relationship", description = """
                    Creates a dated relationship that links an individual person to a commercial account in \
                    one or more roles, optionally marking the person as the primary billing contact.
                    Use this tool when associating a known person with a commercial account; do not use \
                    updateContactRoles, which manages the separate contact-role assignment model, and do not \
                    use createPerson, which creates the person record itself.
                    Preconditions: the commercial party and the person must both exist, and no active \
                    relationship may already cover the same party, person, and role for today's date; \
                    isPrimaryBillingContact requires the BILLING role in the same request.
                    Required inputs: partyId (UUID) as a path parameter, plus personId (UUID), roles (one or \
                    more of APPROVER, BILLING, PRIMARY_CONTACT, DRIVER, TECHNICAL), and effectiveStartDate; \
                    effectiveEndDate and isPrimaryBillingContact (default false) are optional.
                    Emits a CRM_RELATIONSHIP_CREATE event, atomically demotes any existing primary billing \
                    contact when one is designated, and re-emits the person's identity fact.
                    Returns 404 when the party or person cannot be found, 409 when an overlapping active \
                    relationship already exists for a requested role, and 400 when isPrimaryBillingContact \
                    is set without the BILLING role.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Relationship created successfully",
            content = @Content(schema = @Schema(implementation = CreatePartyRelationshipResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request - validation failed")
    @ApiResponse(responseCode = "404", description = "Party or person not found")
    @ApiResponse(responseCode = "409", description = "Conflict - overlapping relationship exists")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden - missing required permission")
    public ResponseEntity<CreatePartyRelationshipResponse> createRelationship(
            @Parameter(description = "The commercial account party ID") @PathVariable UUID partyId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "The person, roles, and effective dates of the relationship being established with the account.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Primary billing contact", value = """
                                                                    {"personId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a60",
                                                                     "roles":["BILLING","PRIMARY_CONTACT"],
                                                                     "isPrimaryBillingContact":true,
                                                                     "effectiveStartDate":"2026-08-13"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreatePartyRelationshipRequest request,
            Principal principal) {

        log.info(
                "Creating relationship: partyId={}, personId={}, roles={}, user={}",
                partyId,
                request.getPersonId(),
                request.getRoles(),
                principal != null ? principal.getName() : ANONYMOUS);

        UUID userId = extractUserId(principal);
        CreatePartyRelationshipResponse response =
                partyRelationshipService.createRelationship(partyId, request, userId);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Retrieves all contacts associated with a commercial account.
     * <p>
     * Supports filtering by roles and status.
     * </p>
     *
     * @param partyId the commercial account party ID
     * @param roles   optional filter by relationship roles
     * @param status  optional filter by status (ACTIVE or INACTIVE)
     * @return contacts with their roles and details
     */
    @GetMapping("/contacts")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"crm:relationship:read"})
    @PreAuthorize("hasAuthority('crm:relationship:read')")
    @EmitEvent(id = "CRM_ACCOUNT_CONTACTS_GET", apiVersion = "1")
    @Operation(
            operationId = "getCommercialAccountContacts",
            summary = "Get Commercial Account Contacts",
            description = """
                    Returns the individuals related to a commercial account through party relationships, \
                    with their roles, primary-billing flag, effective dates, and status.
                    Use this tool when listing who is associated with an account through the relationship \
                    model; use getContactsWithRoles instead for the contact-role assignment view of the \
                    same account.
                    Preconditions: the commercial party must exist.
                    Required inputs: partyId (UUID) as a path parameter; roles optionally filters on \
                    APPROVER, BILLING, PRIMARY_CONTACT, DRIVER, or TECHNICAL, and status optionally filters \
                    on ACTIVE or INACTIVE.
                    Emits a CRM_ACCOUNT_CONTACTS_GET audit event; no state changes occur.
                    Returns 404 when no commercial party exists for the supplied partyId.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Contacts retrieved successfully",
            content = @Content(schema = @Schema(implementation = GetCommercialAccountContactsResponse.class)))
    @ApiResponse(responseCode = "404", description = "Party not found")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden - missing required permission")
    public ResponseEntity<GetCommercialAccountContactsResponse> getContacts(
            @Parameter(description = "The commercial account party ID") @PathVariable UUID partyId,
            @Parameter(description = "Filter by relationship roles") @RequestParam(required = false)
                    List<PartyRelationshipRole> roles,
            @Parameter(description = "Filter by status (ACTIVE or INACTIVE)") @RequestParam(required = false)
                    String status) {

        log.debug("Getting contacts for commercial account: partyId={}, roles={}, status={}", partyId, roles, status);

        GetCommercialAccountContactsResponse response =
                partyRelationshipService.getContactsForCommercialAccount(partyId, roles, status);

        return ResponseEntity.ok(response);
    }

    /**
     * Designates a relationship as the primary billing contact.
     * <p>
     * Atomically demotes any existing primary billing contact for the account.
     * </p>
     *
     * @param partyId        the commercial account party ID
     * @param relationshipId the relationship to designate as primary
     * @param principal      the authenticated user
     * @return no content on success
     */
    @PutMapping("/relationships/{relationshipId}/primary-billing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"crm:relationship:update"})
    @PreAuthorize("hasAuthority('crm:relationship:update')")
    @EmitEvent(id = "CRM_RELATIONSHIP_PRIMARY_BILLING_UPDATE", apiVersion = "1")
    @Operation(
            operationId = "designatePrimaryBillingContact",
            summary = "Designate Primary Billing Contact",
            description = """
                    Promotes an existing relationship to be the account's primary billing contact, \
                    atomically demoting any current primary.
                    Use this tool when the billing contact changes on an account; do not use \
                    createPartyRelationship, which is for a person not yet related to the account and can \
                    set the flag at creation time.
                    Preconditions: the relationship must exist, must belong to the account in the path, \
                    must carry the BILLING role, and must be active as of today.
                    Required inputs: partyId and relationshipId (UUIDs) as path parameters; there is no \
                    request body.
                    Emits a CRM_RELATIONSHIP_PRIMARY_BILLING_UPDATE event; the previous primary, if any, is \
                    demoted in the same transaction.
                    Returns 404 when the relationship does not exist, and 400 when it belongs to a \
                    different party, lacks the BILLING role, or is no longer active.
                    """)
    @ApiResponse(responseCode = "204", description = "Primary billing contact updated")
    @ApiResponse(responseCode = "400", description = "Invalid request - relationship must have BILLING role")
    @ApiResponse(responseCode = "404", description = "Relationship not found")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden - missing required permission")
    public ResponseEntity<Void> designatePrimaryBillingContact(
            @Parameter(description = "The commercial account party ID") @PathVariable UUID partyId,
            @Parameter(description = "The relationship ID to designate as primary") @PathVariable UUID relationshipId,
            Principal principal) {

        log.info(
                "Designating primary billing contact: partyId={}, relationshipId={}, user={}",
                partyId,
                relationshipId,
                principal != null ? principal.getName() : ANONYMOUS);

        UUID userId = extractUserId(principal);
        partyRelationshipService.designatePrimaryBillingContact(partyId, relationshipId, userId);

        return ResponseEntity.noContent().build();
    }

    /**
     * Deactivates a party relationship by setting its end date to today.
     *
     * @param partyId        the commercial account party ID
     * @param relationshipId the relationship ID to deactivate
     * @param principal      the authenticated user
     * @return no content on success
     */
    @DeleteMapping("/relationships/{relationshipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"crm:relationship:delete"})
    @PreAuthorize("hasAuthority('crm:relationship:delete')")
    @EmitEvent(id = "CRM_RELATIONSHIP_DEACTIVATE", apiVersion = "1")
    @Operation(
            operationId = "deactivatePartyRelationship",
            summary = "Deactivate Party Relationship",
            description = """
                    Soft-deletes a relationship between a person and a commercial account by setting its \
                    effective end date to today; the historical record is retained.
                    Use this tool when a person no longer represents the account; do not use \
                    createPartyRelationship to fix a wrong assignment without first deactivating the old \
                    one, since overlapping active roles are rejected.
                    Preconditions: the relationship must exist; the partyId in the path is not validated \
                    against the relationship on this operation.
                    Required inputs: partyId and relationshipId (UUIDs) as path parameters; there is no \
                    request body.
                    Emits a CRM_RELATIONSHIP_DEACTIVATE event and re-emits the person's identity fact \
                    because their account linkage changed.
                    Returns 404 when no relationship exists for the supplied relationshipId.
                    """)
    @ApiResponse(responseCode = "204", description = "Relationship deactivated")
    @ApiResponse(responseCode = "404", description = "Relationship not found")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden - missing required permission")
    public ResponseEntity<Void> deactivateRelationship(
            @Parameter(description = "The commercial account party ID") @PathVariable UUID partyId,
            @Parameter(description = "The relationship ID to deactivate") @PathVariable UUID relationshipId,
            Principal principal) {

        log.info(
                "Deactivating relationship: partyId={}, relationshipId={}, user={}",
                partyId,
                relationshipId,
                principal != null ? principal.getName() : ANONYMOUS);

        UUID userId = extractUserId(principal);
        partyRelationshipService.deactivateRelationship(relationshipId, userId);

        return ResponseEntity.noContent().build();
    }

    private UUID extractUserId(Principal principal) {
        if (principal == null) {
            return null;
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException _) {
            log.warn("Unable to parse user ID from principal name: {}", principal.getName());
            return null;
        }
    }
}
