package com.positivity.customer.controller;

import com.positivity.customer.security.CrmPermissionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CRM Communication Preferences Controller
 * 
 * Handles communication preferences and consent flags for parties.
 * Manages email, SMS, and phone opt-in/opt-out preferences.
 * 
 * Issue #171: Contacts: Store Communication Preferences and Consent Flags
 */
@RestController
@RequestMapping("/v1/crm/parties")
public class CrmCommunicationPreferencesController {

    private static final Logger log = LoggerFactory.getLogger(CrmCommunicationPreferencesController.class);

    /**
     * Get communication preferences for a party.
     * 
     * GET /v1/crm/parties/{partyId}/communicationPreferences
     * Requires: CONTACT_PREFERENCE_VIEW permission
     * 
     * Note: Current backend implementation returns defaults.
     * Preference persistence will be implemented when CommunicationPreference
     * entity is added.
     */
    @GetMapping("/{partyId}/communicationPreferences")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_PREFERENCE_VIEW + "')")
    public ResponseEntity<Void> getCommunicationPreferences(
            @PathVariable String partyId) {
        log.info("Stub getCommunicationPreferences partyId={}", partyId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    /**
     * Create or update communication preferences for a party.
     * 
     * POST /v1/crm/parties/{partyId}/communicationPreferences
     * Requires: CONTACT_PREFERENCE_EDIT permission
     * 
     * Note: Current backend implementation validates but does not persist
     * preferences.
     * Preference persistence will be implemented when CommunicationPreference
     * entity is added.
     */
    @PostMapping("/{partyId}/communicationPreferences")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_PREFERENCE_EDIT + "')")
    public ResponseEntity<Void> upsertCommunicationPreferences(
            @PathVariable String partyId,
            @RequestBody(required = false) Object body) {
        log.info("Stub upsertCommunicationPreferences partyId={}", partyId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
