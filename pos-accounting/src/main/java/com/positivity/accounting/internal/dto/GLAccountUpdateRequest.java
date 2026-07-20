package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.positivity.accounting.internal.enums.AccountSubtype;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for updating an existing GL Account.
 *
 * Only accountName, description, accountSubtype, and reconcilable can be
 * updated. Omitted (null) fields are left unchanged.
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - GLAccount Request</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for updating mutable GL account fields")
public class GLAccountUpdateRequest {

    @Size(max = 100, message = "accountName must not exceed 100 characters")
    @Schema(description = "Updated account name", example = "Accounts Receivable - Trade", requiredMode = NOT_REQUIRED)
    private String accountName;

    @Size(max = 500, message = "description must not exceed 500 characters")
    @Schema(
            description = "Updated account description",
            example = "Primary receivables account",
            requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(
            description = "Updated account subtype refining the immutable accountType for report grouping "
                    + "and posting-config plausibility checks. Left unchanged when omitted.",
            example = "BANK_CASH",
            requiredMode = NOT_REQUIRED)
    private AccountSubtype accountSubtype;

    @Schema(
            description = "Whether journal entry lines on this account participate in reconciliation "
                    + "(e.g. settlement/bank reconciliation). Left unchanged when omitted.",
            example = "true",
            requiredMode = NOT_REQUIRED)
    private Boolean reconcilable;

    @AssertTrue(message = "At least one field (accountName, description, accountSubtype, or reconcilable) is required")
    public boolean hasAtLeastOneFieldToUpdate() {
        return (accountName != null && !accountName.isBlank())
                || (description != null && !description.isBlank())
                || accountSubtype != null
                || reconcilable != null;
    }
}
