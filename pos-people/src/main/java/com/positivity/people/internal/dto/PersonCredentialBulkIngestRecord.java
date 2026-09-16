package com.positivity.people.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Data;

/** One credential row of a bulk ingest (CAP-328). */
@Data
@Schema(description = "One person's credential: what it certifies, who issued it, and when")
public class PersonCredentialBulkIngestRecord {

    @Schema(description = "Employee number resolving to the person", example = "EMP-0006", requiredMode = REQUIRED)
    @NotBlank
    private String employeeNumber;

    @Schema(
            description = "Durion skill code, when the credential is not a vendor's (e.g. DOT-INSPECTOR). Either"
                    + " this or sourceCode + sourceCredentialCode.",
            example = "DOT-INSPECTOR",
            requiredMode = NOT_REQUIRED)
    private String skillCode;

    @Schema(description = "Vendor code system", example = "ASE", requiredMode = NOT_REQUIRED)
    private String sourceCode;

    @Schema(
            description = "Vendor's code, resolved through the cross-reference",
            example = "T4-BRAKES",
            requiredMode = NOT_REQUIRED)
    private String sourceCredentialCode;

    @Schema(description = "Issuer; defaults to sourceCode", example = "ASE", requiredMode = NOT_REQUIRED)
    private String issuer;

    @Schema(description = "Issue date", example = "2024-03-15", requiredMode = REQUIRED)
    @NotNull
    private LocalDate issuedOn;

    @Schema(
            description = "Expiry date; omit when it does not expire",
            example = "2029-03-15",
            requiredMode = NOT_REQUIRED)
    private LocalDate expiresOn;

    @Schema(description = "Display metadata only, 1-5", example = "4", requiredMode = NOT_REQUIRED)
    @Min(1)
    @Max(5)
    private Integer proficiency;

    @Schema(description = "Retained evidence document id", requiredMode = NOT_REQUIRED)
    private UUID evidenceRef;
}
