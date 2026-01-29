package com.positivity.customer.internal.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Response DTO for retrieving communication preferences for a party.
 * Issue #171: Contacts: Manage Communication Preferences & Consent Flags
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GetCommunicationPreferencesResponse {

    /**
     * Party ID queried
     */
    private String partyId;

    /**
     * Optimistic locking version field (name TBD: version|lastUpdatedStamp|ETag)
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
     * Last update timestamp (ISO 8601)
     */
    private String updatedAt;

    /**
     * Source of last update (APP|API|ADMIN|IMPORT)
     */
    private String updateSource;
}
