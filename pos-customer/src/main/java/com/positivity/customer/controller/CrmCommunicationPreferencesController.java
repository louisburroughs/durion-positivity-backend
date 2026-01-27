package com.positivity.customer.controller;

import com.positivity.customer.security.CrmPermissionRegistry;
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
 * CRM Communication Preferences Controller
 * 
 * Handles communication preferences and consent flags for parties.
 * Manages email, SMS, and phone opt-in/opt-out preferences.
 * 
 * Issue #171: Contacts: Store Communication Preferences and Consent Flags
 */
@Tag(name = "CRM Communication Preferences", description = "Communication preferences and consent flag management (stub endpoints)")
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
    @Operation(summary = "Get communication preferences", description = "Retrieve communication preferences and consent flags for a party (stub endpoint)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "501", description = "Not implemented", content = @Content),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping("/{partyId}/communicationPreferences")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_PREFERENCE_VIEW + "')")
    public ResponseEntity<Void> getCommunicationPreferences(
            @Parameter(description = "Party ID", required = true) @PathVariable String partyId) {
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
    @Operation(summary = "Create or update communication preferences", description = "Set or update communication preferences and consent flags for a party (stub endpoint)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "501", description = "Not implemented", content = @Content),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping("/{partyId}/communicationPreferences")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_PREFERENCE_EDIT + "')")
    public ResponseEntity<Void> upsertCommunicationPreferences(
            @Parameter(description = "Party ID", required = true) @PathVariable String partyId,
            @Parameter(description = "Communication preferences to set", required = false) @RequestBody(required = false) Object body) {
        log.info("Stub upsertCommunicationPreferences partyId={}", partyId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
