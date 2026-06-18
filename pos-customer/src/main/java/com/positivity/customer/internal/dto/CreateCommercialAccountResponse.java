package com.positivity.customer.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for creating a commercial account.
 * Issue #176: Party: Create Commercial Account
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Response after creating a commercial account")
public class CreateCommercialAccountResponse {

    @NotNull
    @Schema(
            description = "Newly created party ID (canonical CRM identifier)",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String partyId;

    @NotNull
    @Schema(
            description = "Legal name of created party",
            example = "Acme Industrial Supply, LLC",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String legalName;

    @NotNull
    @Schema(
            description = "Status (ACTIVE|PENDING|SUSPENDED)",
            example = "ACTIVE",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String status;

    @NotNull
    @Schema(
            description = "Human-readable customer/account number (party number) for display and lookup",
            example = "CUST-000123",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String customerNumber;

    @Schema(
            description = "Display name of the account, if provided (falls back to legal name in UI)",
            example = "Acme Industrial",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String displayName;

    @Schema(
            description = "Party type discriminator",
            example = "COMMERCIAL",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String partyType;

    @Schema(
            description = "Tax identifier, if provided",
            example = "12-3456789",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String taxId;

    @Schema(
            description = "Billing terms identifier associated with the account, if set",
            example = "NET30",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String billingTermsId;

    @Schema(
            description = "Primary address for display, if available",
            example = "100 Trade Street, Charlotte, NC 28202",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String primaryAddress;

    @NotNull
    @Schema(
            description = "Timestamp of creation",
            example = "2026-01-01T12:00:00Z",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "List of duplicate candidates found during creation. If present, frontend should prompt"
                    + " user for confirmation. Contains summary fields only (partyId, legalName, matchReason).",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private List<DuplicateCandidate> duplicateCandidates;

    /**
     * Wrapper for duplicate candidates (returned on 409 or as advisory in 201)
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Duplicate candidate summary returned on 409 or as advisory in 201")
    public static class DuplicateCandidate {
        @NotNull
        @Schema(
                description = "Candidate party ID",
                example = "01960003-0000-7000-8000-000000000002",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String partyId;

        @NotNull
        @Schema(
                description = "Candidate legal name",
                example = "Acme Industrial Supply Co.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String legalName;

        @NotNull
        @Schema(
                description = "Reason this candidate matched",
                example = "LEGAL_NAME_SIMILARITY",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String matchReason;
    }
}
