package com.positivity.customer.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Role types for party relationships.
 * Defines the role an individual plays in relation to a commercial account.
 */
@Schema(description = "Role type for party relationships")
public enum PartyRelationshipRole {
    @Schema(description = "Authorized to approve workorders and invoices")
    APPROVER,

    @Schema(description = "Receives billing communications and invoices")
    BILLING,

    @Schema(description = "Primary contact for the account")
    PRIMARY_CONTACT,

    @Schema(description = "Driver associated with fleet vehicles")
    DRIVER,

    @Schema(description = "Technical contact for service-related communications")
    TECHNICAL
}
