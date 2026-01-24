package com.positivity.customer.controller;

import com.positivity.customer.entity.*;
import com.positivity.customer.security.CrmPermissionRegistry;
import com.positivity.customer.service.PartyService;
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
@RestController
@RequestMapping("/v1/crm")
public class CrmAccountsController {

    private static final Logger log = LoggerFactory.getLogger(CrmAccountsController.class);

    private final PartyService partyService;

    public CrmAccountsController(PartyService partyService) {
        this.partyService = partyService;
    }

    @GetMapping("/accounts/{accountId}/tier")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    public ResponseEntity<Void> getAccountTier(@PathVariable String accountId) {
        log.info("Stub getAccountTier accountId={}", accountId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping("/accounts/tierResolve")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    public ResponseEntity<Void> resolveAccountTiers(@RequestBody(required = false) Object body) {
        log.info("Stub resolveAccountTiers");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    // --- Party/Commercial Account Management (Issue #176) ---

    @PostMapping("/parties")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_CREATE + "')")
    public ResponseEntity<CreateCommercialAccountResponse> createCommercialAccount(
            @RequestBody(required = false) CreateCommercialAccountRequest body) {
        log.info("createCommercialAccount");
        CreateCommercialAccountResponse response = partyService.createCommercialAccount(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/parties/{partyId}")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    public ResponseEntity<GetPartyResponse> getParty(@PathVariable String partyId) {
        log.info("getParty partyId={}", partyId);
        GetPartyResponse response = partyService.getParty(partyId);
        return ResponseEntity.ok(response);
    }

    // --- Party Search and Merge (Issue #173) ---

    @PostMapping("/parties/search")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    public ResponseEntity<SearchPartiesResponse> searchParties(
            @RequestBody(required = false) SearchPartiesRequest body) {
        log.info("searchParties");
        SearchPartiesResponse response = partyService.searchParties(body);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/parties/{partyId}/merge")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_MERGE + "')")
    public ResponseEntity<MergePartiesResponse> mergeParties(@PathVariable String partyId,
            @RequestBody(required = false) MergePartiesRequest body) {
        log.info("mergeParties partyId={}", partyId);
        MergePartiesResponse response = partyService.mergeParties(partyId, body);
        return ResponseEntity.ok(response);
    }

    // --- Contacts and Roles Management (Issue #172) ---

    @GetMapping("/parties/{partyId}/contacts")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    public ResponseEntity<GetContactsWithRolesResponse> getContactsWithRoles(@PathVariable String partyId) {
        log.info("getContactsWithRoles partyId={}", partyId);
        GetContactsWithRolesResponse response = partyService.getContactsWithRoles(partyId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/parties/{partyId}/contacts/{contactId}/roles")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_ROLE_ASSIGN + "')")
    public ResponseEntity<UpdateContactRolesResponse> updateContactRoles(@PathVariable String partyId,
            @PathVariable String contactId,
            @RequestBody(required = false) UpdateContactRolesRequest body) {
        log.info("updateContactRoles partyId={} contactId={}", partyId, contactId);
        UpdateContactRolesResponse response = partyService.updateContactRoles(partyId, contactId, body);
        return ResponseEntity.ok(response);
    }

    // --- Communication Preferences (Issue #171) ---

    @GetMapping("/parties/{partyId}/communicationPreferences")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_PREFERENCE_VIEW + "')")
    public ResponseEntity<GetCommunicationPreferencesResponse> getCommunicationPreferences(
            @PathVariable String partyId) {
        log.info("getCommunicationPreferences partyId={}", partyId);
        GetCommunicationPreferencesResponse response = partyService.getCommunicationPreferences(partyId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/parties/{partyId}/communicationPreferences")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_PREFERENCE_EDIT + "')")
    public ResponseEntity<UpsertCommunicationPreferencesResponse> upsertCommunicationPreferences(
            @PathVariable String partyId,
            @RequestBody(required = false) UpsertCommunicationPreferencesRequest body) {
        log.info("upsertCommunicationPreferences partyId={}", partyId);
        UpsertCommunicationPreferencesResponse response = partyService.upsertCommunicationPreferences(partyId, body);
        return ResponseEntity.ok(response);
    }

    // --- Vehicle Management (Issue #169) ---

    @PostMapping("/parties/{partyId}/vehicles")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.VEHICLE_CREATE + "')")
    public ResponseEntity<CreateVehicleForPartyResponse> createVehicleForParty(@PathVariable String partyId,
            @RequestBody(required = false) CreateVehicleForPartyRequest body) {
        log.info("createVehicleForParty partyId={}", partyId);
        CreateVehicleForPartyResponse response = partyService.createVehicleForParty(partyId, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
