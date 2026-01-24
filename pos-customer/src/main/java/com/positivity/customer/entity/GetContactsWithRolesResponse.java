package com.positivity.customer.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for retrieving contacts with role assignments for a party.
 * Issue #172: Contacts: Maintain Contact Roles and Primary Flags
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GetContactsWithRolesResponse {

    /**
     * Party ID queried
     */
    private String partyId;

    /**
     * List of contacts with assigned roles
     */
    private List<ContactWithRoles> contacts;

    /**
     * Contact detail with role assignments
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContactWithRoles {

        /**
         * Contact point ID (person/contact identifier)
         */
        private String contactId;

        /**
         * Contact name
         */
        private String contactName;

        /**
         * Email address
         */
        private String email;

        /**
         * Phone number
         */
        private String phone;

        /**
         * Flag: contact has at least one primary email on file
         */
        private Boolean hasPrimaryEmail;

        /**
         * Assigned roles (BILLING|APPROVER|DRIVER or dynamic list)
         */
        private List<AssignedRole> roles;

        /**
         * Invoice delivery method for this contact (EMAIL|PHONE|NONE)
         */
        private String invoiceDeliveryMethod;
    }

    /**
     * Assigned role details
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AssignedRole {

        /**
         * Role code (BILLING|APPROVER|DRIVER)
         */
        private String roleCode;

        /**
         * Role display label
         */
        private String roleLabel;

        /**
         * Flag: this contact is primary for this role
         */
        private Boolean isPrimary;
    }
}
