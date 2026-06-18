package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for upserting communication preferences for a party.
 * Issue #171: Contacts: Manage Communication Preferences & Consent Flags
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Request to upsert communication preferences and consent flags for a party")
public class UpsertCommunicationPreferencesRequest {

    /**
     * Optimistic locking version field (name TBD: version|lastUpdatedStamp|ETag)
     * Required for updates, omitted for creates.
     */
    @Schema(
            description = "Optimistic locking version; required for updates, omitted for creates",
            example = "5",
            requiredMode = NOT_REQUIRED)
    @Size(max = 100)
    private String version;

    /**
     * Email preference (OPT_IN|OPT_OUT|NOT_APPLICABLE)
     */
    @Schema(
            description = "Email preference (OPT_IN|OPT_OUT|NOT_APPLICABLE)",
            example = "OPT_IN",
            requiredMode = NOT_REQUIRED)
    @Size(max = 40)
    private String emailPreference;

    /**
     * SMS preference (OPT_IN|OPT_OUT|NOT_APPLICABLE)
     */
    @Schema(
            description = "SMS preference (OPT_IN|OPT_OUT|NOT_APPLICABLE)",
            example = "OPT_OUT",
            requiredMode = NOT_REQUIRED)
    @Size(max = 40)
    private String smsPreference;

    /**
     * Phone preference (OPT_IN|OPT_OUT|NOT_APPLICABLE)
     */
    @Schema(
            description = "Phone preference (OPT_IN|OPT_OUT|NOT_APPLICABLE)",
            example = "OPT_IN",
            requiredMode = NOT_REQUIRED)
    @Size(max = 40)
    private String phonePreference;

    /**
     * Marketing communications preference (OPT_IN|OPT_OUT)
     */
    @Schema(
            description = "Marketing communications preference (OPT_IN|OPT_OUT)",
            example = "OPT_OUT",
            requiredMode = NOT_REQUIRED)
    @Size(max = 40)
    private String marketingPreference;

    /**
     * Consent flags (field names TBD based on legal requirements)
     * Can be separate ConsentRecord entity or stored here.
     */
    @Schema(
            description = "Consent flags keyed by consent type; field names depend on legal requirements",
            example = "{\"gdpr\": true, \"ccpa\": false}",
            requiredMode = NOT_REQUIRED)
    private Map<String, Boolean> consentFlags;

    /**
     * User-provided note or preferences summary
     */
    @Schema(
            description = "User-provided note or preferences summary",
            example = "Prefers email contact only on weekdays",
            requiredMode = NOT_REQUIRED)
    @Size(max = 1000)
    private String preferencesNote;

    /**
     * Source of update (frontend sends constant or backend infers from context).
     * TBD: frontend sends (APP|API|ADMIN) or backend always sets to APP if client.
     */
    @Schema(
            description = "Source of update (APP|API|ADMIN)",
            example = "APP",
            requiredMode = NOT_REQUIRED)
    @Size(max = 40)
    private String updateSource;
}
