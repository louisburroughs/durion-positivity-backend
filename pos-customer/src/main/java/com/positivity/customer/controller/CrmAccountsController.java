package com.positivity.customer.controller;

import com.positivity.customer.entity.*;
import com.positivity.customer.security.CrmPermissionRegistry;
import com.positivity.customer.service.PartyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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
 * Stub controller implementing CRM account-tier endpoints from the API catalog.
 *
 * Intentionally unimplemented: returns 501 for all operations.
 */
@Tag(name = "CRM Accounts", description = "Account tier management, party creation, search, merge, contacts, preferences, and vehicle operations")
@RestController
@RequestMapping("/v1/crm/accounts")
public class CrmAccountsController {

        private static final Logger log = LoggerFactory.getLogger(CrmAccountsController.class);

        private final PartyService partyService;

        public CrmAccountsController(PartyService partyService) {
                this.partyService = partyService;
        }

        @Operation(summary = "Get account tier", description = "Retrieve the tier level for a specific account (stub endpoint)")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "501", description = "Not implemented", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
        })
        @GetMapping("/{accountId}/tier")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
        public ResponseEntity<Void> getAccountTier(
                        @Parameter(description = "Account ID", required = true) @PathVariable String accountId) {
                log.info("Stub getAccountTier accountId={}", accountId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @Operation(summary = "Resolve account tier", description = "Resolve or compute the account tier based on business rules (stub endpoint)")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "501", description = "Not implemented", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
        })
        @PostMapping("/tierResolve")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
        public ResponseEntity<Void> resolveAccountTier(
                        @Parameter(description = "Tier resolution request", required = false) @RequestBody(required = false) Object body) {
                log.info("Stub resolveAccountTiers");
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        // --- Party/Commercial Account Management (Issue #176) ---

        @Operation(summary = "Create commercial account", description = "Create a new commercial party/account in the CRM system")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "201", description = "Account created successfully", content = @Content(schema = @Schema(implementation = CreateCommercialAccountResponse.class))),
                        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
        })
        @PostMapping("/parties")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_CREATE + "')")
        public ResponseEntity<CreateCommercialAccountResponse> createCommercialAccount(
                        @Parameter(description = "Commercial account creation request", required = false) @RequestBody(required = false) CreateCommercialAccountRequest body) {
                log.info("createCommercialAccount");
                CreateCommercialAccountResponse response = partyService.createCommercialAccount(body);
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }

        @Operation(summary = "Get party details", description = "Retrieve details for a specific party by ID")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "Party details retrieved successfully", content = @Content(schema = @Schema(implementation = GetPartyResponse.class))),
                        @ApiResponse(responseCode = "404", description = "Party not found", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
        })
        @GetMapping("/parties/{partyId}")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
        public ResponseEntity<GetPartyResponse> getParty(
                        @Parameter(description = "Party ID", required = true) @PathVariable String partyId) {
                log.info("getParty partyId={}", partyId);
                GetPartyResponse response = partyService.getParty(partyId);
                return ResponseEntity.ok(response);
        }

        // --- Party Search and Merge (Issue #173) ---

        @Operation(summary = "Search parties", description = "Search for parties based on various criteria")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "Search results returned", content = @Content(schema = @Schema(implementation = SearchPartiesResponse.class))),
                        @ApiResponse(responseCode = "400", description = "Invalid search criteria", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
        })
        @PostMapping("/parties/search")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
        public ResponseEntity<SearchPartiesResponse> searchParties(
                        @Parameter(description = "Search criteria", required = false) @RequestBody(required = false) SearchPartiesRequest body) {
                log.info("searchParties");
                SearchPartiesResponse response = partyService.searchParties(body);
                return ResponseEntity.ok(response);
        }

        @Operation(summary = "Merge parties", description = "Merge multiple parties into a single party record")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "Parties merged successfully", content = @Content(schema = @Schema(implementation = MergePartiesResponse.class))),
                        @ApiResponse(responseCode = "404", description = "Party not found", content = @Content),
                        @ApiResponse(responseCode = "400", description = "Invalid merge request", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
        })
        @PostMapping("/parties/{partyId}/merge")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_MERGE + "')")
        public ResponseEntity<MergePartiesResponse> mergeParties(
                        @Parameter(description = "Target party ID", required = true) @PathVariable String partyId,
                        @Parameter(description = "Merge request with source party IDs", required = false) @RequestBody(required = false) MergePartiesRequest body) {
                log.info("mergeParties partyId={}", partyId);
                MergePartiesResponse response = partyService.mergeParties(partyId, body);
                return ResponseEntity.ok(response);
        }

        // --- Contacts and Roles Management (Issue #172) ---

        @Operation(summary = "Get contacts with roles", description = "Retrieve all contacts for a party including their role assignments")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "Contacts retrieved successfully", content = @Content(schema = @Schema(implementation = GetContactsWithRolesResponse.class))),
                        @ApiResponse(responseCode = "404", description = "Party not found", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
        })
        @GetMapping("/parties/{partyId}/contacts")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_VIEW + "')")
        public ResponseEntity<GetContactsWithRolesResponse> getContactsWithRoles(
                        @Parameter(description = "Party ID", required = true) @PathVariable String partyId) {
                log.info("getContactsWithRoles partyId={}", partyId);
                GetContactsWithRolesResponse response = partyService.getContactsWithRoles(partyId);
                return ResponseEntity.ok(response);
        }

        @Operation(summary = "Update contact roles", description = "Assign or update role assignments for a specific contact within a party")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "Roles updated successfully", content = @Content(schema = @Schema(implementation = UpdateContactRolesResponse.class))),
                        @ApiResponse(responseCode = "404", description = "Party or contact not found", content = @Content),
                        @ApiResponse(responseCode = "400", description = "Invalid role assignment", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
        })
        @PutMapping("/parties/{partyId}/contacts/{contactId}/roles")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_ROLE_ASSIGN + "')")
        public ResponseEntity<UpdateContactRolesResponse> updateContactRoles(
                        @Parameter(description = "Party ID", required = true) @PathVariable String partyId,
                        @Parameter(description = "Contact ID", required = true) @PathVariable String contactId,
                        @Parameter(description = "Role update request", required = false) @RequestBody(required = false) UpdateContactRolesRequest body) {
                log.info("updateContactRoles partyId={} contactId={}", partyId, contactId);
                UpdateContactRolesResponse response = partyService.updateContactRoles(partyId, contactId, body);
                return ResponseEntity.ok(response);
        }

        // --- Communication Preferences (Issue #171) ---

        @Operation(summary = "Get communication preferences", description = "Retrieve communication preferences and consent flags for a party")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "Preferences retrieved successfully", content = @Content(schema = @Schema(implementation = GetCommunicationPreferencesResponse.class))),
                        @ApiResponse(responseCode = "404", description = "Party not found", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
        })
        @GetMapping("/parties/{partyId}/communicationPreferences")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_PREFERENCE_VIEW + "')")
        public ResponseEntity<GetCommunicationPreferencesResponse> getCommunicationPreferences(
                        @Parameter(description = "Party ID", required = true) @PathVariable String partyId) {
                log.info("getCommunicationPreferences partyId={}", partyId);
                GetCommunicationPreferencesResponse response = partyService.getCommunicationPreferences(partyId);
                return ResponseEntity.ok(response);
        }

        @Operation(summary = "Create or update communication preferences", description = "Set or update communication preferences and consent flags for a party")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "Preferences updated successfully", content = @Content(schema = @Schema(implementation = UpsertCommunicationPreferencesResponse.class))),
                        @ApiResponse(responseCode = "404", description = "Party not found", content = @Content),
                        @ApiResponse(responseCode = "400", description = "Invalid preference data", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
        })
        @PostMapping("/parties/{partyId}/communicationPreferences")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_PREFERENCE_EDIT + "')")
        public ResponseEntity<UpsertCommunicationPreferencesResponse> upsertCommunicationPreferences(
                        @Parameter(description = "Party ID", required = true) @PathVariable String partyId,
                        @Parameter(description = "Communication preferences to set", required = false) @RequestBody(required = false) UpsertCommunicationPreferencesRequest body) {
                log.info("upsertCommunicationPreferences partyId={}", partyId);
                UpsertCommunicationPreferencesResponse response = partyService.upsertCommunicationPreferences(partyId,
                                body);
                return ResponseEntity.ok(response);
        }

        // --- Vehicle Management (Issue #169) ---

        @Operation(summary = "Create vehicle for party", description = "Associate a new vehicle with a party/customer")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "201", description = "Vehicle created successfully", content = @Content(schema = @Schema(implementation = CreateVehicleForPartyResponse.class))),
                        @ApiResponse(responseCode = "404", description = "Party not found", content = @Content),
                        @ApiResponse(responseCode = "400", description = "Invalid vehicle data", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
        })
        @PostMapping("/parties/{partyId}/vehicles")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.VEHICLE_CREATE + "')")
        public ResponseEntity<CreateVehicleForPartyResponse> createVehicleForParty(
                        @Parameter(description = "Party ID", required = true) @PathVariable String partyId,
                        @Parameter(description = "Vehicle creation request", required = false) @RequestBody(required = false) CreateVehicleForPartyRequest body) {
                log.info("createVehicleForParty partyId={}", partyId);
                CreateVehicleForPartyResponse response = partyService.createVehicleForParty(partyId, body);
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }
}
