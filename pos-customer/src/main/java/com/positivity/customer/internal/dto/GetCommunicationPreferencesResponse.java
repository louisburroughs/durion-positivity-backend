package com.positivity.customer.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for retrieving communication preferences for a party.
 * Issue #171: Contacts: Manage Communication Preferences & Consent Flags
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Communication preferences and consent flags for a party")
public class GetCommunicationPreferencesResponse {

    /**
     * Party ID queried
     */
    @NotBlank
    @Schema(
            description = "Party identifier the preferences belong to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String partyId;

    /**
     * Optimistic locking version field (name TBD: version|lastUpdatedStamp|ETag)
     */
    @Schema(
            description = "Optimistic locking version token for concurrent updates",
            example = "3",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String version;

    /**
     * Email preference (OPT_IN|OPT_OUT|NOT_APPLICABLE)
     */
    @Schema(
            description = "Email communication preference",
            example = "OPT_IN",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            allowableValues = {"OPT_IN", "OPT_OUT", "NOT_APPLICABLE"})
    private String emailPreference;

    /**
     * SMS preference (OPT_IN|OPT_OUT|NOT_APPLICABLE)
     */
    @Schema(
            description = "SMS communication preference",
            example = "OPT_OUT",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            allowableValues = {"OPT_IN", "OPT_OUT", "NOT_APPLICABLE"})
    private String smsPreference;

    /**
     * Phone preference (OPT_IN|OPT_OUT|NOT_APPLICABLE)
     */
    @Schema(
            description = "Phone communication preference",
            example = "OPT_IN",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            allowableValues = {"OPT_IN", "OPT_OUT", "NOT_APPLICABLE"})
    private String phonePreference;

    /**
     * Marketing communications preference (OPT_IN|OPT_OUT)
     */
    @Schema(
            description = "Marketing communications preference",
            example = "OPT_OUT",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            allowableValues = {"OPT_IN", "OPT_OUT"})
    private String marketingPreference;

    /**
     * Consent flags (field names TBD based on legal requirements)
     * Can be separate ConsentRecord entity or stored here.
     */
    @Schema(
            description = "Consent flags keyed by consent type",
            example = "{\"gdpr\":true,\"tcpa\":false}",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Map<String, Boolean> consentFlags;

    /**
     * User-provided note or preferences summary
     */
    @Schema(
            description = "User-provided note or preferences summary",
            example = "Prefers contact after 5pm",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String preferencesNote;

    /**
     * Last update timestamp (ISO 8601)
     */
    @Schema(
            description = "Last update timestamp (ISO 8601)",
            example = "2026-06-17T10:15:30Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String updatedAt;

    /**
     * Source of last update (APP|API|ADMIN|IMPORT)
     */
    @Schema(
            description = "Source of the last update",
            example = "API",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            allowableValues = {"APP", "API", "ADMIN", "IMPORT"})
    private String updateSource;
}
