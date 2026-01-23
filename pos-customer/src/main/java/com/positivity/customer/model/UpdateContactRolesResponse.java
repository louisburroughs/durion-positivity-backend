package com.positivity.customer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for updating contact role assignments.
 * Issue #172: Contacts: Maintain Contact Roles and Primary Flags
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateContactRolesResponse {

    /**
     * Party ID
     */
    private String partyId;

    /**
     * Contact ID that was updated
     */
    private String contactId;

    /**
     * Updated version (for optimistic locking conflict resolution)
     */
    private String version;

    /**
     * Update status (SUCCESS|CONFLICT)
     */
    private String status;

    /**
     * Timestamp of update (ISO 8601)
     */
    private String updatedAt;
}
