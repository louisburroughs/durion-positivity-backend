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
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
                        "crm:relationship:create" })
        @PreAuthorize("hasAuthority('crm:relationship:create')")
        @EmitEvent(id = "CRM_RELATIONSHIP_CREATE", apiVersion = "1")
        @Operation(summary = "Create a party relationship", description = "Creates a relationship between a commercial account and an individual person")
        @ApiResponse(responseCode = "201", description = "Relationship created successfully", content = @Content(schema = @Schema(implementation = CreatePartyRelationshipResponse.class)))
        @ApiResponse(responseCode = "400", description = "Invalid request - validation failed")
        @ApiResponse(responseCode = "404", description = "Party or person not found")
        @ApiResponse(responseCode = "409", description = "Conflict - overlapping relationship exists")
        @ApiResponse(responseCode = "401", description = "Unauthorized")
        @ApiResponse(responseCode = "403", description = "Forbidden - missing required permission")
        public ResponseEntity<CreatePartyRelationshipResponse> createRelationship(
                        @Parameter(description = "The commercial account party ID") @PathVariable UUID partyId,
                        @Valid @RequestBody CreatePartyRelationshipRequest request,
                        Principal principal) {

                log.info(
                                "Creating relationship: partyId={}, personId={}, roles={}, user={}",
                                partyId,
                                request.getPersonId(),
                                request.getRoles(),
                                principal != null ? principal.getName() : ANONYMOUS);

                UUID userId = extractUserId(principal);
                CreatePartyRelationshipResponse response = partyRelationshipService.createRelationship(partyId, request,
                                userId);

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
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
                        "crm:relationship:read" })
        @PreAuthorize("hasAuthority('crm:relationship:read')")
        @EmitEvent(id = "CRM_ACCOUNT_CONTACTS_GET", apiVersion = "1")
        @Operation(summary = "Get contacts for a commercial account", description = "Retrieves all individuals associated with a commercial account with their roles")
        @ApiResponse(responseCode = "200", description = "Contacts retrieved successfully", content = @Content(schema = @Schema(implementation = GetCommercialAccountContactsResponse.class)))
        @ApiResponse(responseCode = "404", description = "Party not found")
        @ApiResponse(responseCode = "401", description = "Unauthorized")
        @ApiResponse(responseCode = "403", description = "Forbidden - missing required permission")
        public ResponseEntity<GetCommercialAccountContactsResponse> getContacts(
                        @Parameter(description = "The commercial account party ID") @PathVariable UUID partyId,
                        @Parameter(description = "Filter by relationship roles") @RequestParam(required = false) List<PartyRelationshipRole> roles,
                        @Parameter(description = "Filter by status (ACTIVE or INACTIVE)") @RequestParam(required = false) String status) {

                log.debug("Getting contacts for commercial account: partyId={}, roles={}, status={}", partyId, roles,
                                status);

                GetCommercialAccountContactsResponse response = partyRelationshipService
                                .getContactsForCommercialAccount(partyId, roles, status);

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
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
                        "crm:relationship:update" })
        @PreAuthorize("hasAuthority('crm:relationship:update')")
        @EmitEvent(id = "CRM_RELATIONSHIP_PRIMARY_BILLING_UPDATE", apiVersion = "1")
        @Operation(summary = "Designate primary billing contact", description = "Sets a relationship as the primary billing contact, demoting any existing primary")
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
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
                        "crm:relationship:delete" })
        @PreAuthorize("hasAuthority('crm:relationship:delete')")
        @EmitEvent(id = "CRM_RELATIONSHIP_DEACTIVATE", apiVersion = "1")
        @Operation(summary = "Deactivate a party relationship", description = "Soft-deletes a relationship by setting its effective end date")
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
