package com.positivity.customer.internal.dto;

import com.positivity.customer.internal.enums.PartyRelationshipRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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

    @NotNull
    @Schema(
            description = "ID of the commercial account",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String commercialAccountId;

    @Valid
    @NotNull
    @Schema(
            description = "List of contacts with their roles",
            requiredMode = Schema.RequiredMode.REQUIRED)
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

        @NotNull
        @Schema(
                description = "Relationship ID",
                example = "01960003-0000-7000-8000-000000000010",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private UUID relationshipId;

        @NotNull
        @Schema(
                description = "Individual person ID",
                example = "01960003-0000-7000-8000-000000000011",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private UUID individualId;

        @Schema(
                description = "Roles assigned to this contact",
                example = "[\"BILLING\"]",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        private Set<PartyRelationshipRole> roles;

        @Schema(
                description = "Whether this is the primary billing contact",
                example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private boolean isPrimaryBilling;

        @Schema(
                description = "Status of the relationship",
                example = "ACTIVE",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String status;

        @Schema(
                description = "Effective start date",
                example = "2026-01-01",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        private LocalDate effectiveFrom;

        @Schema(
                description = "Effective end date (null if active)",
                example = "2026-12-31",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        private LocalDate effectiveTo;

        @Valid
        @Schema(
                description = "Individual person details",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
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

        @Schema(
                description = "Display name",
                example = "John Doe",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String displayName;

        @Schema(
                description = "Primary email address",
                example = "john.doe@acme.com",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        private String email;

        @Schema(
                description = "Primary phone number",
                example = "+1-555-0142",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        private String phone;
    }
}
