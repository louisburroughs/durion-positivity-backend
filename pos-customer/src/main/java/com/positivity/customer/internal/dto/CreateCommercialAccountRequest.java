package com.positivity.customer.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a commercial account (party).
 * Issue #176: Party: Create Commercial Account
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Request to create a commercial account (party)")
public class CreateCommercialAccountRequest {

    @NotBlank(message = "legalName is required")
    @Size(max = 255)
    @Schema(
            description = "Legal business name",
            example = "Acme Industrial Supply, LLC",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String legalName;

    @Size(max = 255)
    @Schema(
            description = "Display/trading name",
            example = "Acme Supply",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String displayName;

    @Size(max = 64)
    @Schema(
            description = "Tax identification number (required for certain jurisdictions)",
            example = "12-3456789",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String taxId;

    @Size(max = 32)
    @Schema(
            description = "Party type (ORGANIZATION|INDIVIDUAL; default ORGANIZATION for commercial accounts)",
            example = "ORGANIZATION",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String partyType;

    @Schema(
            description = "Billing terms ID (foreign key to Billing domain)",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String billingTermsId;

    @Schema(
            description = "External identifiers (system-specific IDs from upstream systems)",
            example = "{\"erp\": \"CUST-00123\"}",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Map<String, String> externalIdentifiers;

    @Size(max = 255)
    @Schema(
            description = "Primary contact first name",
            example = "Jane",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String contactFirstName;

    @Size(max = 255)
    @Schema(
            description = "Primary contact last name",
            example = "Smith",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String contactLastName;

    @Email
    @Size(max = 255)
    @Schema(
            description = "Contact email",
            example = "jane@acme.com",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String email;

    @Size(max = 64)
    @Schema(
            description = "Contact phone",
            example = "+1-555-123-4567",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String phone;
}
