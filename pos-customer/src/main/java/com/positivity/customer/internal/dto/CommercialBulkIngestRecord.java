package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Single record within a bulk commercial-account ingest request. Optionally
 * carries one primary contact person that is created and attached to the new
 * account in the same row.
 */
@Data
@Schema(description = "A single commercial account record within a bulk ingest request")
public class CommercialBulkIngestRecord {

    @Schema(
            description = "Registered legal name of the commercial account",
            example = "Piedmont Freight Carriers LLC",
            requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 255)
    private String legalName;

    @Schema(
            description = "Display name; defaults to the legal name when omitted",
            example = "Piedmont Freight",
            requiredMode = NOT_REQUIRED)
    @Size(max = 255)
    private String displayName;

    @Schema(description = "Tax identifier (EIN)", example = "27-4481203", requiredMode = NOT_REQUIRED)
    @Size(max = 64)
    private String taxId;

    @Schema(description = "Billing terms identifier", example = "NET-30", requiredMode = NOT_REQUIRED)
    @Size(max = 255)
    private String billingTermsId;

    @Schema(
            description = "First name of the primary contact to create and attach; requires contactLastName",
            example = "Dale",
            requiredMode = NOT_REQUIRED)
    @Size(max = 255)
    private String contactFirstName;

    @Schema(
            description = "Last name of the primary contact to create and attach; requires contactFirstName",
            example = "Whitfield",
            requiredMode = NOT_REQUIRED)
    @Size(max = 255)
    private String contactLastName;

    // Deliberately no @Email: dirty legacy addresses must fail per-row (or be
    // stored as provided), not 400 the whole batch — same policy as
    // CustomerBulkIngestRecord.
    @Schema(
            description = "Primary email of the contact person",
            example = "dale.whitfield@piedmontfreight.example.com",
            requiredMode = NOT_REQUIRED)
    @Size(max = 254)
    private String contactEmail;

    @Schema(description = "Primary phone of the contact person", example = "+17045550188", requiredMode = NOT_REQUIRED)
    @Size(max = 64)
    private String contactPhone;
}
