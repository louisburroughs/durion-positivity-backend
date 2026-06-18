package com.positivity.customer.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for retrieving contacts with role assignments for a party.
 * Issue #172: Contacts: Maintain Contact Roles and Primary Flags
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Contacts with their assigned roles and primary flags for a party")
public class GetContactsWithRolesResponse {

    /**
     * Party ID queried
     */
    @NotBlank
    @Schema(
            description = "Party identifier the contacts belong to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String partyId;

    /**
     * List of contacts with assigned roles
     */
    @Valid
    @NotNull
    @Schema(
            description = "List of contacts with assigned roles",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<ContactWithRoles> contacts;

    /**
     * Contact detail with role assignments
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "A single contact with its assigned roles")
    public static class ContactWithRoles {

        /**
         * Contact point ID (person/contact identifier)
         */
        @NotBlank
        @Schema(
                description = "Contact point identifier (person/contact)",
                example = "01960003-0000-7000-8000-000000000020",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String contactId;

        /**
         * Contact name
         */
        @Schema(
                description = "Contact name",
                example = "Jane Doe",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String contactName;

        /**
         * Email address
         */
        @Schema(
                description = "Email address",
                example = "jane@acme.com",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        private String email;

        /**
         * Phone number
         */
        @Schema(
                description = "Phone number",
                example = "+1-555-0142",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        private String phone;

        /**
         * Flag: contact has at least one primary email on file
         */
        @Schema(
                description = "Whether the contact has at least one primary email on file",
                example = "true",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        private Boolean hasPrimaryEmail;

        /**
         * Assigned roles (BILLING|APPROVER|DRIVER or dynamic list)
         */
        @Valid
        @Schema(
                description = "Assigned roles for this contact",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        private List<AssignedRole> roles;

        /**
         * Invoice delivery method for this contact (EMAIL|PHONE|NONE)
         */
        @Schema(
                description = "Invoice delivery method for this contact",
                example = "EMAIL",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                allowableValues = {"EMAIL", "PHONE", "NONE"})
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
    @Schema(description = "An assigned role with its primary flag")
    public static class AssignedRole {

        /**
         * Role code (BILLING|APPROVER|DRIVER)
         */
        @NotBlank
        @Schema(
                description = "Role code",
                example = "BILLING",
                requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"BILLING", "APPROVER", "DRIVER"})
        private String roleCode;

        /**
         * Role display label
         */
        @Schema(
                description = "Role display label",
                example = "Billing Contact",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        private String roleLabel;

        /**
         * Flag: this contact is primary for this role
         */
        @Schema(
                description = "Whether this contact is primary for this role",
                example = "true",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        private Boolean isPrimary;
    }
}
