package com.positivity.customer.internal.dto;

import com.positivity.customer.internal.enums.PartyRelationshipRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a party relationship.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/110">Backend
 *      Issue #110</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to create a relationship between a commercial account and an individual")
public class CreatePartyRelationshipRequest {

    @NotNull(message = "personId is required")
    @Schema(
            description = "ID of the individual person",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID personId;

    @NotEmpty(message = "At least one role is required")
    @Schema(
            description = "Roles for this relationship",
            example = "[\"BILLING\", \"APPROVER\"]",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Set<PartyRelationshipRole> roles;

    @Schema(description = "Whether this should be the primary billing contact", example = "true")
    private boolean isPrimaryBillingContact;

    @NotNull(message = "effectiveStartDate is required")
    @Schema(
            description = "Date when this relationship becomes effective",
            example = "2026-01-01",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate effectiveStartDate;

    @Schema(description = "Date when this relationship ends (null means no end date)", example = "2026-12-31")
    private LocalDate effectiveEndDate;
}
