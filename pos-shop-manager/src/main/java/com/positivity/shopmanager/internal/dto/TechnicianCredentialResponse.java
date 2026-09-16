package com.positivity.shopmanager.internal.dto;

import com.positivity.shopmanager.internal.enums.CredentialStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One credential on a roster entry (CAP-328). Replaces the bare skill-code list the rosters used
 * to flatten to, so an expired certification no longer reads as a held skill.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A credential the technician holds, with its status on the roster's reference date")
public class TechnicianCredentialResponse {

    @Schema(description = "Credential id in the People domain", example = "01960011-0000-7000-8000-000000000031")
    private UUID credentialId;

    @Schema(description = "Durion skill code from the registry", example = "BRAKES-MEDIUM_HEAVY")
    private String skillCode;

    @Schema(description = "Competence the skill certifies, independent of duty class", example = "BRAKES")
    private String competenceCode;

    @Schema(description = "Lowest GVWR class (1-8) the skill covers", example = "4")
    private int minGvwrClass;

    @Schema(description = "Highest GVWR class (1-8) the skill covers", example = "8")
    private int maxGvwrClass;

    @Schema(description = "Issuing body", example = "ASE")
    private String issuer;

    @Schema(description = "The issuer's own code for the credential, as received", example = "T4-BRAKES")
    private String sourceCredentialCode;

    @Schema(description = "Date the credential was issued", example = "2024-03-15")
    private LocalDate issuedOn;

    @Schema(description = "Date the credential expires; null never expires", example = "2029-03-15")
    private LocalDate expiresOn;

    @Schema(description = "Display-only proficiency 1-5; no requirement thresholds on it", example = "4")
    private Integer proficiency;

    @Schema(
            description = "Status on the roster's reference date: expiry is judged there, not taken from the feed",
            example = "ACTIVE")
    private CredentialStatus status;
}
