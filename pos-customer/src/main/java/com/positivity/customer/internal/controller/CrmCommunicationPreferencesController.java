package com.positivity.customer.internal.controller;

import java.util.regex.Pattern;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.customer.internal.dto.GetCommunicationPreferencesResponse;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesRequest;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesResponse;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.service.CommunicationPreferenceService;
import com.positivity.events.EmitEvent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * CRM Communication Preferences Controller
 * 
 * Handles communication preferences and consent flags for parties.
 * Manages email, SMS, and phone opt-in/opt-out preferences.
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/107">Backend
 *      Issue #107</a>
 */
@Tag(name = "CRM Communication Preferences", description = "Communication preferences and consent flag management")
@RestController
@RequestMapping("/v1/crm/parties")
public class CrmCommunicationPreferencesController {

    private static final Logger log = LoggerFactory.getLogger(CrmCommunicationPreferencesController.class);

    // Pattern for valid UUID format to prevent injection attacks
    private static final Pattern VALID_PARTY_ID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final CommunicationPreferenceService preferenceService;

    public CrmCommunicationPreferencesController(CommunicationPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    /**
     * Get communication preferences for a party.
     * 
     * GET /v1/crm/parties/{partyId}/communicationPreferences
     * Requires: CONTACT_PREFERENCE_VIEW permission
     */
    @Operation(summary = "Get communication preferences", description = "Retrieve communication preferences and consent flags for a party")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Preferences retrieved successfully", content = @Content(schema = @Schema(implementation = GetCommunicationPreferencesResponse.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content),
            @ApiResponse(responseCode = "404", description = "Party not found", content = @Content)
    })
    @GetMapping("/{partyId}/communicationPreferences")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_PREFERENCE_VIEW + "')")
    @EmitEvent(id = "CRM_COMMUNICATION_PREFERENCES_GET", apiVersion = "1")
    public ResponseEntity<GetCommunicationPreferencesResponse> getCommunicationPreferences(
            @Parameter(description = "Party ID", required = true) @PathVariable @NonNull String partyId) {

        // Sanitize partyId - must be valid UUID format
        if (!VALID_PARTY_ID_PATTERN.matcher(partyId).matches()) {
            log.warn("Invalid partyId format: {}", partyId);
            return ResponseEntity.badRequest().build();
        }

        try {
            GetCommunicationPreferencesResponse response = preferenceService.getCommunicationPreferences(partyId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to get communication preferences: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Create or update communication preferences for a party.
     * 
     * POST /v1/crm/parties/{partyId}/communicationPreferences
     * Requires: CONTACT_PREFERENCE_EDIT permission
     */
    @Operation(summary = "Create or update communication preferences", description = "Set or update communication preferences and consent flags for a party")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Preferences updated successfully", content = @Content(schema = @Schema(implementation = UpsertCommunicationPreferencesResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content),
            @ApiResponse(responseCode = "404", description = "Party not found", content = @Content)
    })
    @PostMapping("/{partyId}/communicationPreferences")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_PREFERENCE_EDIT + "')")
    @EmitEvent(id = "CRM_COMMUNICATION_PREFERENCES_UPSERT", apiVersion = "1")
    public ResponseEntity<UpsertCommunicationPreferencesResponse> upsertCommunicationPreferences(
            @Parameter(description = "Party ID", required = true) @PathVariable @NonNull String partyId,
            @Parameter(description = "Communication preferences to set", required = true) @RequestBody @NonNull UpsertCommunicationPreferencesRequest request) {

        // Sanitize partyId - must be valid UUID format
        if (!VALID_PARTY_ID_PATTERN.matcher(partyId).matches()) {
            log.warn("Invalid partyId format: {}", partyId);
            return ResponseEntity.badRequest().build();
        }

        try {
            UpsertCommunicationPreferencesResponse response = preferenceService.upsertCommunicationPreferences(partyId,
                    request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to upsert communication preferences: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}