package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.GetCommunicationPreferencesResponse;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesRequest;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesResponse;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.service.CommunicationPreferenceService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    @Operation(
            operationId = "getCommunicationPreferences",
            summary = "Get Communication Preferences",
            description = """
                    Returns the persisted communication preferences and consent flags for a party, covering \
                    email, SMS, phone, and marketing channels plus the free-form consent flag map.
                    Use this tool when reading a party's current contact-channel opt-ins; do not use \
                    getAccountCommunicationPreferences, a legacy accounts-scoped stub that only returns N/A \
                    placeholders.
                    Preconditions: the party must exist as either a commercial or person party; a party with \
                    no stored preference record is reported with every channel defaulted to OPT_OUT and \
                    version 0.
                    Required inputs: partyId (UUID) as a path parameter; there is no request body.
                    Emits a CRM_COMMUNICATION_PREFERENCES_GET audit event; no state changes occur.
                    Returns 404 when no party exists for the supplied partyId.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Preferences retrieved successfully",
                        content =
                                @Content(schema = @Schema(implementation = GetCommunicationPreferencesResponse.class))),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content),
                @ApiResponse(responseCode = "404", description = "Party not found", content = @Content)
            })
    @GetMapping("/{partyId}/communicationPreferences")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.CONTACT_PREFERENCE_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_PREFERENCE_VIEW + "')")
    @EmitEvent(id = "CRM_COMMUNICATION_PREFERENCES_GET", apiVersion = "1")
    public ResponseEntity<GetCommunicationPreferencesResponse> getCommunicationPreferences(
            @Parameter(description = "Party ID", required = true) @PathVariable @NonNull UUID partyId) {

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
    @Operation(
            operationId = "upsertCommunicationPreferences",
            summary = "Upsert Communication Preferences",
            description = """
                    Creates or updates the persisted communication-preference record for a party, replacing \
                    channel preferences, consent flags, and the preferences note.
                    Use this tool when recording a party's contact-channel opt-in or opt-out choices; do not \
                    use upsertAccountCommunicationPreferences, a legacy accounts-scoped stub that does not \
                    persist anything.
                    Preconditions: the party must exist as either a commercial or person party.
                    Required inputs: partyId (UUID) as a path parameter and a JSON body; omitted \
                    emailPreference, smsPreference, phonePreference, or marketingPreference values default to \
                    OPT_OUT, and updateSource defaults to APP.
                    Emits a CRM_COMMUNICATION_PREFERENCES_UPSERT event and writes the preference record, \
                    reporting operationType CREATED or UPDATED with a new version.
                    Returns 404 when no party exists for the supplied partyId.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Preferences updated successfully",
                        content =
                                @Content(
                                        schema =
                                                @Schema(
                                                        implementation =
                                                                UpsertCommunicationPreferencesResponse.class))),
                @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content),
                @ApiResponse(responseCode = "404", description = "Party not found", content = @Content)
            })
    @PostMapping("/{partyId}/communicationPreferences")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.CONTACT_PREFERENCE_EDIT})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.CONTACT_PREFERENCE_EDIT + "')")
    @EmitEvent(id = "CRM_COMMUNICATION_PREFERENCES_UPSERT", apiVersion = "1")
    public ResponseEntity<UpsertCommunicationPreferencesResponse> upsertCommunicationPreferences(
            @Parameter(description = "Party ID", required = true) @PathVariable @NonNull UUID partyId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Channel preferences, consent flags, and audit source to store for the party; omitted channels default to OPT_OUT.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Email opt-in with consent flags",
                                                            value = """
                                                                    {"emailPreference":"OPT_IN",
                                                                     "smsPreference":"OPT_OUT",
                                                                     "phonePreference":"OPT_OUT",
                                                                     "marketingPreference":"OPT_IN",
                                                                     "consentFlags":{"serviceReminders":true,"promotions":false},
                                                                     "preferencesNote":"Customer asked for email only",
                                                                     "updateSource":"ADMIN"}
                                                                    """)))
                    @RequestBody
                    @NonNull
                    UpsertCommunicationPreferencesRequest request) {

        try {
            UpsertCommunicationPreferencesResponse response =
                    preferenceService.upsertCommunicationPreferences(partyId, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to upsert communication preferences: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}
