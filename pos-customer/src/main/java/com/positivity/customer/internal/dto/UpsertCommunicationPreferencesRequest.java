package com.positivity.customer.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request DTO for upserting communication preferences for a party.
 * Issue #171: Contacts: Manage Communication Preferences & Consent Flags
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpsertCommunicationPreferencesRequest {

    /**
     * Optimistic locking version field (name TBD: version|lastUpdatedStamp|ETag)
     * Required for updates, omitted for creates.
     */
    private String version;

    /**
     * Email preference (OPT_IN|OPT_OUT|NOT_APPLICABLE)
     */
    private String emailPreference;

    /**
     * SMS preference (OPT_IN|OPT_OUT|NOT_APPLICABLE)
     */
    private String smsPreference;

    /**
     * Phone preference (OPT_IN|OPT_OUT|NOT_APPLICABLE)
     */
    private String phonePreference;

    /**
     * Marketing communications preference (OPT_IN|OPT_OUT)
     */
    private String marketingPreference;

    /**
     * Consent flags (field names TBD based on legal requirements)
     * Can be separate ConsentRecord entity or stored here.
     */
    private Map<String, Boolean> consentFlags;

    /**
     * User-provided note or preferences summary
     */
    private String preferencesNote;

    /**
     * Source of update (frontend sends constant or backend infers from context).
     * TBD: frontend sends (APP|API|ADMIN) or backend always sets to APP if client.
     */
    private String updateSource;
}
