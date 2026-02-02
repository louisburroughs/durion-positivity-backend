package com.positivity.customer.internal.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

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

    @Schema(description = "Unique identifier of the relationship")
    private UUID relationshipId;

    @Schema(description = "ID of the commercial account")
    private String partyId;

    @Schema(description = "ID of the individual person")
    private UUID personId;

    @Schema(description = "Roles assigned to this relationship")
    private Set<PartyRelationshipRole> roles;

    @Schema(description = "Whether this is the primary billing contact")
    private boolean isPrimaryBillingContact;

    @Schema(description = "Effective start date")
    private LocalDate effectiveStartDate;

    @Schema(description = "Effective end date (null if active)")
    private LocalDate effectiveEndDate;

    @Schema(description = "Timestamp when the relationship was created")
    private Instant createdAt;

    @Schema(description = "Whether a previous primary billing contact was demoted")
    private boolean previousPrimaryDemoted;
}
