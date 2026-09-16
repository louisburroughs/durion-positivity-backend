package com.positivity.people.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/** A credential a person holds, with the registry skill it certifies (CAP-328). */
@Value
@Builder
@Schema(description = "A credential a person holds")
public class PersonCredentialResponse {

    @Schema(requiredMode = REQUIRED)
    UUID credentialId;

    @Schema(requiredMode = REQUIRED)
    UUID personId;

    @Schema(requiredMode = REQUIRED)
    UUID skillId;

    @Schema(description = "Durion skill code", example = "BRAKES-MEDIUM_HEAVY", requiredMode = REQUIRED)
    String skillCode;

    @Schema(example = "BRAKES", requiredMode = REQUIRED)
    String competenceCode;

    @Schema(description = "Lowest FHWA GVWR class the skill certifies work on", requiredMode = REQUIRED)
    int minGvwrClass;

    @Schema(description = "Highest FHWA GVWR class the skill certifies work on", requiredMode = REQUIRED)
    int maxGvwrClass;

    @Schema(example = "ASE", requiredMode = REQUIRED)
    String issuer;

    @Schema(
            description = "Vendor code system the credential arrived under",
            example = "ASE",
            requiredMode = NOT_REQUIRED)
    String sourceCode;

    @Schema(example = "T4-BRAKES", requiredMode = NOT_REQUIRED)
    String sourceCredentialCode;

    @Schema(requiredMode = REQUIRED)
    LocalDate issuedOn;

    @Schema(description = "Null: does not expire", requiredMode = NOT_REQUIRED)
    LocalDate expiresOn;

    @Schema(description = "Display metadata only; nothing thresholds on it", requiredMode = NOT_REQUIRED)
    Integer proficiency;

    @Schema(
            description = "ACTIVE or EXPIRED as the dates say today; REVOKED or SUPERSEDED when set deliberately",
            allowableValues = {"ACTIVE", "EXPIRED", "REVOKED", "SUPERSEDED"},
            requiredMode = REQUIRED)
    String status;

    @Schema(description = "Retained evidence document (49 CFR 396.19)", requiredMode = NOT_REQUIRED)
    UUID evidenceRef;
}
