package com.positivity.customer.internal.dto;

import com.positivity.customer.internal.enums.PartyRelationshipRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for party relationship creation.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/110">Backend
 *      Issue #110</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response after creating a party relationship")
public class CreatePartyRelationshipResponse {

    @NotNull
    @Schema(
            description = "Unique identifier of the relationship",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID relationshipId;

    @NotNull
    @Schema(
            description = "ID of the commercial account",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String partyId;

    @NotNull
    @Schema(
            description = "ID of the individual person",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID personId;

    @NotNull
    @Schema(
            description = "Roles assigned to this relationship",
            example = "[\"BILLING\", \"APPROVER\"]",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Set<PartyRelationshipRole> roles;

    @Schema(
            description = "Whether this is the primary billing contact",
            example = "true",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean isPrimaryBillingContact;

    @NotNull
    @Schema(description = "Effective start date", example = "2026-01-01", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate effectiveStartDate;

    @Schema(
            description = "Effective end date (null if active)",
            example = "2026-12-31",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private LocalDate effectiveEndDate;

    @NotNull
    @Schema(
            description = "Timestamp when the relationship was created",
            example = "2026-01-01T12:00:00Z",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Whether a previous primary billing contact was demoted",
            example = "false",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean previousPrimaryDemoted;
}
