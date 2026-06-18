package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for retrieving a party by partyId.
 * Issue #176: Party: Create Commercial Account
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Party details for a commercial account or individual party")
public class GetPartyResponse {

    /**
     * Party ID (canonical CRM identifier)
     */
    @Schema(
            description = "Party ID (canonical CRM identifier)",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotBlank
    private String partyId;

    /**
     * Party type (ORGANIZATION|INDIVIDUAL)
     */
    @Schema(
            description = "Party type discriminator",
            example = "ORGANIZATION",
            requiredMode = REQUIRED)
    @NotBlank
    private String partyType;

    /**
     * Legal name
     */
    @Schema(
            description = "Legal name of the party",
            example = "Acme Industrial Supply LLC",
            requiredMode = REQUIRED)
    @NotBlank
    private String legalName;

    /**
     * Display/trading name
     */
    @Schema(
            description = "Display/trading name",
            example = "Acme Supply",
            requiredMode = NOT_REQUIRED)
    private String displayName;

    /**
     * Tax identification number
     */
    @Schema(
            description = "Tax identification number",
            example = "82-1234567",
            requiredMode = NOT_REQUIRED)
    private String taxId;

    /**
     * Current status (ACTIVE|PENDING|SUSPENDED|INACTIVE)
     */
    @Schema(
            description = "Current status of the party",
            example = "ACTIVE",
            requiredMode = REQUIRED)
    @NotBlank
    private String status;

    /**
     * Billing terms ID
     */
    @Schema(
            description = "Billing terms ID",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = NOT_REQUIRED)
    private String billingTermsId;

    /**
     * Party creation timestamp (ISO 8601)
     */
    @Schema(
            description = "Party creation timestamp (ISO 8601)",
            example = "2026-01-15T10:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private String createdAt;

    /**
     * Last modification timestamp (ISO 8601)
     */
    @Schema(
            description = "Last modification timestamp (ISO 8601)",
            example = "2026-02-20T14:45:00Z",
            requiredMode = NOT_REQUIRED)
    private String modifiedAt;
}
