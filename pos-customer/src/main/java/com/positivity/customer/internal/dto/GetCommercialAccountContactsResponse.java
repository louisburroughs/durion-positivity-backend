package com.positivity.customer.internal.dto;

import com.positivity.customer.internal.enums.PartyRelationshipRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for listing contacts with roles for a commercial account.
 * Implements the consumer API contract defined in Issue #110.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/110">Backend
 *      Issue #110</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Contacts with roles for a commercial account")
public class GetCommercialAccountContactsResponse {

    @Schema(description = "ID of the commercial account")
    private String commercialAccountId;

    @Schema(description = "List of contacts with their roles")
    private List<ContactWithRole> contacts;

    /**
     * Contact with role details.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Contact information with role assignments")
    public static class ContactWithRole {

        @Schema(description = "Relationship ID")
        private UUID relationshipId;

        @Schema(description = "Individual person ID")
        private UUID individualId;

        @Schema(description = "Roles assigned to this contact")
        private Set<PartyRelationshipRole> roles;

        @Schema(description = "Whether this is the primary billing contact")
        private boolean isPrimaryBilling;

        @Schema(description = "Status of the relationship", example = "ACTIVE")
        private String status;

        @Schema(description = "Effective start date")
        private LocalDate effectiveFrom;

        @Schema(description = "Effective end date (null if active)")
        private LocalDate effectiveTo;

        @Schema(description = "Individual person details")
        private IndividualDetails individual;
    }

    /**
     * Individual person details for embedding in contact response.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Individual person details")
    public static class IndividualDetails {

        @Schema(description = "Display name", example = "John Doe")
        private String displayName;

        @Schema(description = "Primary email address")
        private String email;

        @Schema(description = "Primary phone number")
        private String phone;
    }
}
