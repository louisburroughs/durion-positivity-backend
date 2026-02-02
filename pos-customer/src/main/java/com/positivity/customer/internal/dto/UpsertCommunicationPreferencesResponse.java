package com.positivity.customer.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for upserting communication preferences.
 * Issue #171: Contacts: Manage Communication Preferences & Consent Flags
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpsertCommunicationPreferencesResponse {

    /**
     * Party ID updated
     */
    private String partyId;

    /**
     * Updated version (for optimistic locking)
     */
    private String version;

    /**
     * Operation type (CREATED|UPDATED)
     */
    private String operationType;

    /**
     * Update status (SUCCESS|CONFLICT)
     */
    private String status;

    /**
     * Timestamp of update (ISO 8601)
     */
    private String updatedAt;
}
