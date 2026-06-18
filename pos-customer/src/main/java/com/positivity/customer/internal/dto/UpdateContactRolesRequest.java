package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for updating contact role assignments.
 * Issue #172: Contacts: Maintain Contact Roles and Primary Flags
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Request to update the set of role assignments for a contact")
public class UpdateContactRolesRequest {

    /**
     * Optimistic locking version field (name TBD: version|lastUpdatedStamp|ETag)
     * Required if backend enforces optimistic locking.
     */
    @Schema(
            description = "Optimistic locking version; required when the backend enforces optimistic locking",
            example = "7",
            requiredMode = NOT_REQUIRED)
    @Size(max = 100)
    private String version;

    /**
     * List of roles to assign to this contact
     */
    @Schema(description = "List of roles to assign to this contact", requiredMode = REQUIRED)
    @NotNull
    @Valid
    private List<RoleAssignment> roles;

    /**
     * Role assignment detail
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Single role assignment detail")
    public static class RoleAssignment {

        /**
         * Role code (BILLING|APPROVER|DRIVER)
         */
        @Schema(description = "Role code (BILLING|APPROVER|DRIVER)", example = "BILLING", requiredMode = REQUIRED)
        @NotBlank
        @Size(max = 40)
        private String roleCode;

        /**
         * Flag: set this contact as primary for this role
         */
        @Schema(
                description = "Flag: set this contact as primary for this role",
                example = "true",
                requiredMode = NOT_REQUIRED)
        private Boolean isPrimary;
    }
}
