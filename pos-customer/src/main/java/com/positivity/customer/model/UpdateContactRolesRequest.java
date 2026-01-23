package com.positivity.customer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for updating contact role assignments.
 * Issue #172: Contacts: Maintain Contact Roles and Primary Flags
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateContactRolesRequest {

    /**
     * Optimistic locking version field (name TBD: version|lastUpdatedStamp|ETag)
     * Required if backend enforces optimistic locking.
     */
    private String version;

    /**
     * List of roles to assign to this contact
     */
    private List<RoleAssignment> roles;

    /**
     * Role assignment detail
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RoleAssignment {

        /**
         * Role code (BILLING|APPROVER|DRIVER)
         */
        private String roleCode;

        /**
         * Flag: set this contact as primary for this role
         */
        private Boolean isPrimary;
    }
}
